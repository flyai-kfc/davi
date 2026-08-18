#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/platform-tools/adb" ]]; then
    ADB="$SDK_ROOT/platform-tools/adb"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
    SDK_ROOT="$HOME/Library/Android/sdk"
    ADB="$SDK_ROOT/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
else
    ADB="${SDK_ROOT:+$SDK_ROOT/platform-tools/}adb"
fi
SERVER_URL="http://127.0.0.1:8000"
RUNNER="com.davi.helper.e2edriver.test/com.davi.helper.e2e.DaviE2eRunner"
SERVICE="com.davi.helper/com.davi.helper.GuideAccessibilityService"
REPORT_RENDERER="$SCRIPT_DIR/render-device-e2e-report.py"
PRIVATE_REPORT_RENDERER="$SCRIPT_DIR/render-device-e2e-private-report.py"
SCENARIO_REGISTRY="$APP_DIR/device-e2e-scenarios.json"
SCENARIO=""
LIST_SCENARIOS=false
DETAILED_REPORT=true
PRIVATE_TRACE_AVAILABLE=false
DISABLE_PRIVACY_MASKING=false

usage() {
    cat <<'EOF'
사용법: ./scripts/run-device-e2e.sh [옵션]

옵션:
  --scenario ID      실행할 시나리오 ID (생략 시 registry 기본값)
  --list-scenarios   실행 가능한 시나리오 목록 출력
  --no-detailed-report  로컬 상세 요청·응답 보고서를 생성하지 않음
  --disable-privacy-masking
                     앱 1차 마스킹을 끈 debug APK로 설치해 실행한다(#203 비교 실험).
                     서버도 DAVI_DISABLE_PRIVACY_MASKING=true로 띄워야 두 겹 모두 우회된다.
                     원문 화면 label이 실제 Gemini까지 나가므로 합성 데이터만 넣은
                     전용 테스트 기기에서만 사용한다. 상세 보고서는 자동으로 꺼진다.
  -h, --help         도움말 출력
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario)
            if [[ $# -lt 2 ]]; then printf '%s\n' '--scenario 값이 필요합니다.' >&2; exit 2; fi
            SCENARIO="$2"
            shift 2
            ;;
        --list-scenarios) LIST_SCENARIOS=true; shift ;;
        --no-detailed-report) DETAILED_REPORT=false; shift ;;
        --disable-privacy-masking) DISABLE_PRIVACY_MASKING=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf '알 수 없는 옵션: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ "$LIST_SCENARIOS" == true ]]; then
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1], encoding="utf-8")); [print("{}\t{}".format(s["id"], s["description"])) for s in d["scenarios"]]' "$SCENARIO_REGISTRY"
    exit 0
fi

if [[ -z "$SCENARIO" ]]; then
    SCENARIO="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["default_scenario"])' "$SCENARIO_REGISTRY")"
fi
if ! python3 -c 'import json,sys; d=json.load(open(sys.argv[1], encoding="utf-8")); any(x["id"] == sys.argv[2] for x in d["scenarios"]) or sys.exit(2)' "$SCENARIO_REGISTRY" "$SCENARIO"; then
    printf '등록되지 않은 시나리오: %s\n' "$SCENARIO" >&2
    printf '%s\n' '--list-scenarios로 목록을 확인하세요.' >&2
    exit 2
fi
TEST_CLASS="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1], encoding="utf-8")); print(next(x for x in d["scenarios"] if x["id"] == sys.argv[2])["test_class"])' "$SCENARIO_REGISTRY" "$SCENARIO")"
SCENARIO_DESCRIPTION="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1], encoding="utf-8")); print(next(x for x in d["scenarios"] if x["id"] == sys.argv[2])["description"])' "$SCENARIO_REGISTRY" "$SCENARIO")"

if [[ ! -x "$ADB" ]]; then
    printf 'Android SDK의 adb를 찾을 수 없습니다. ANDROID_HOME을 확인하세요.\n' >&2
    exit 2
fi

if ! HEALTH_JSON="$(curl --fail --silent --show-error "$SERVER_URL/health")"; then
    printf '127.0.0.1:8000의 서버 health를 확인할 수 없습니다.\n' >&2
    exit 2
fi
if ! printf '%s' "$HEALTH_JSON" |
    python3 -c 'import json,sys; data=json.load(sys.stdin); assert data.get("stub_llm") is False'; then
    printf '127.0.0.1:8000에 DAVI_USE_STUB_LLM=false인 서버가 필요합니다.\n' >&2
    exit 2
fi
MODEL="$(printf '%s' "$HEALTH_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("model", "unknown"))')"
# 서버 2차 마스킹을 우회한 실행은 상세 보고서를 만들지 않는다(#203). 상세 보고서는 화면 label과
# 모델 문장을 그대로 담으므로, 우회 실행에서는 그 파일이 원문 화면을 디스크에 남기게 된다.
# 우회 실험이 남겨야 하는 것은 성공 여부·단계 수·실패 코드 같은 비식별 지표뿐이다.
SERVER_MASKING_DISABLED="$(printf '%s' "$HEALTH_JSON" |
    python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("privacy_masking_disabled", False)).lower())')"
if [[ "$SERVER_MASKING_DISABLED" == true || "$DISABLE_PRIVACY_MASKING" == true ]]; then
    if [[ "$DETAILED_REPORT" == true ]]; then
        printf '마스킹 우회 실행이라 상세 보고서 생성을 끕니다(#203).\n' >&2
    fi
    DETAILED_REPORT=false
fi
# 두 겹의 상태를 각각 알린다. 한쪽만 우회한 실행도 유효한 비교지만, 어느 겹이 켜져 있었는지
# 모른 채 결과를 읽으면 안 된다(#203 안전 경계).
printf '개인정보 마스킹 — 앱 1차: %s / 서버 2차: %s\n' \
    "$(if [[ "$DISABLE_PRIVACY_MASKING" == true ]]; then echo '우회'; else echo '적용'; fi)" \
    "$(if [[ "$SERVER_MASKING_DISABLED" == true ]]; then echo '우회'; else echo '적용'; fi)" >&2

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    SERIAL="$ANDROID_SERIAL"
else
    # 무선 adb mDNS serial에는 공백이 들어갈 수 있어 일반 공백 분리가 아니라 탭 열을 쓴다.
    DEVICES="$("$ADB" devices | awk -F '\t' 'NR > 1 && $2 == "device" {print $1}')"
    DEVICE_COUNT="$(printf '%s\n' "$DEVICES" | awk 'NF {count++} END {print count+0}')"
    if [[ "$DEVICE_COUNT" -ne 1 ]]; then
        printf '기기가 정확히 하나 연결되어야 합니다. 여러 대면 ANDROID_SERIAL을 지정하세요.\n' >&2
        exit 2
    fi
    SERIAL="$DEVICES"
fi

ADB_DEVICE=("$ADB" -s "$SERIAL")
REVERSE_WAS_PRESENT=false
DAVI_DISABLED=false
WITHOUT_DAVI=""
INSTRUMENTATION_RESULT="$(mktemp -t davi-e2e-result.XXXXXX)"
SAFE_LOG_RESULT="$(mktemp -t davi-e2e-safe-log.XXXXXX)"
PRIVATE_TRACE_RESULT="$(mktemp -t davi-e2e-private-trace.XXXXXX)"
RUN_STARTED_AT="$(date '+%Y-%m-%dT%H:%M:%S%z')"
REPORT_STAMP="$(python3 -c 'from datetime import datetime; n=datetime.now().astimezone(); print(n.strftime("%Y%m%dT%H%M%S") + f"{n.microsecond // 1000:03d}" + n.strftime("%z"))')"
REPORT_DIR="$APP_DIR/build/reports/device-e2e/$SCENARIO"
REPORT_PATH="$REPORT_DIR/$REPORT_STAMP.md"
LATEST_REPORT="$REPORT_DIR/latest.md"
PRIVATE_REPORT_DIR="$APP_DIR/build/reports/device-e2e-private/$SCENARIO"
PRIVATE_REPORT_PATH="$PRIVATE_REPORT_DIR/$REPORT_STAMP.md"
LATEST_PRIVATE_REPORT="$PRIVATE_REPORT_DIR/latest.md"
DEVICE_PRIVATE_TRACE="/sdcard/Android/data/com.davi.helper.e2edriver/files/davi-e2e-private-trace.jsonl"
DEVICE_MODEL="$("${ADB_DEVICE[@]}" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$("${ADB_DEVICE[@]}" shell getprop ro.build.version.release | tr -d '\r')"
GIT_COMMIT="$(git -C "$APP_DIR/.." rev-parse --short HEAD)"
if "${ADB_DEVICE[@]}" reverse --list | awk '{print $2}' | grep -qx 'tcp:8000'; then
    REVERSE_WAS_PRESENT=true
else
    "${ADB_DEVICE[@]}" reverse tcp:8000 tcp:8000
fi

restore_davi_accessibility() {
    if [[ "$DAVI_DISABLED" != true ]]; then return; fi
    local with_davi="${WITHOUT_DAVI:+$WITHOUT_DAVI:}$SERVICE"
    "${ADB_DEVICE[@]}" shell settings put secure enabled_accessibility_services "$with_davi" >/dev/null
    "${ADB_DEVICE[@]}" shell settings put secure accessibility_enabled 1 >/dev/null
    DAVI_DISABLED=false
}

cleanup() {
    restore_davi_accessibility || true
    rm -f "$INSTRUMENTATION_RESULT" "$SAFE_LOG_RESULT" "$PRIVATE_TRACE_RESULT"
    # Davi 앱은 유지하고 launcher가 없는 테스트 전용 helper/test 패키지만 치운다.
    "${ADB_DEVICE[@]}" uninstall com.davi.helper.e2edriver.test >/dev/null 2>&1 || true
    "${ADB_DEVICE[@]}" uninstall com.davi.helper.e2edriver >/dev/null 2>&1 || true
    if [[ "$REVERSE_WAS_PRESENT" == false ]]; then
        "${ADB_DEVICE[@]}" reverse --remove tcp:8000 >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

if [[ -n "$SDK_ROOT" ]]; then
    export ANDROID_HOME="$SDK_ROOT"
    export ANDROID_SDK_ROOT="$SDK_ROOT"
fi
if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

cd "$APP_DIR"
# 설치가 접근성 서비스 프로세스를 중단할 때 crashed 상태가 남지 않도록 Davi 항목만 먼저 끈다.
# 다른 접근성 서비스와 전역 접근성 설정은 유지하고, 설치 실패 시에도 EXIT trap이 Davi를 복구한다.
ENABLED_SERVICES="$("${ADB_DEVICE[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$ENABLED_SERVICES" == "null" ]]; then ENABLED_SERVICES=""; fi
ACCESSIBILITY_ENABLED="$("${ADB_DEVICE[@]}" shell settings get secure accessibility_enabled | tr -d '\r')"
if [[ ":$ENABLED_SERVICES:" != *":$SERVICE:"* || "$ACCESSIBILITY_ENABLED" != "1" ]]; then
    printf '실행 전에 기기 설정에서 Davi 접근성 서비스를 직접 켜세요.\n' >&2
    exit 2
fi
IFS=':' read -r -a ACCESSIBILITY_PARTS <<< "$ENABLED_SERVICES"
for ITEM in "${ACCESSIBILITY_PARTS[@]}"; do
    if [[ -n "$ITEM" && "$ITEM" != "$SERVICE" ]]; then
        WITHOUT_DAVI="${WITHOUT_DAVI:+$WITHOUT_DAVI:}$ITEM"
    fi
done
if [[ -n "$WITHOUT_DAVI" ]]; then
    "${ADB_DEVICE[@]}" shell settings put secure enabled_accessibility_services "$WITHOUT_DAVI"
else
    "${ADB_DEVICE[@]}" shell settings delete secure enabled_accessibility_services >/dev/null
fi
DAVI_DISABLED=true

# signature 권한을 선언하는 Davi debug 앱을 먼저 설치한 뒤 그 권한을 요청하는 driver를 설치한다.
# 우회 속성은 켠 실행에서만 넘긴다. 안 넘기면 debug BuildConfig 기본값이 그대로 마스킹 활성이라,
# 직전 실행이 우회 APK였더라도 이 설치가 마스킹된 APK로 되돌린다(#203).
APP_INSTALL_ARGS=(-PdaviE2eServerUrl="$SERVER_URL")
if [[ "$DISABLE_PRIVACY_MASKING" == true ]]; then
    APP_INSTALL_ARGS+=(-PdaviDisablePrivacyMasking=true)
fi
./gradlew "${APP_INSTALL_ARGS[@]}" :app:installDebug
./gradlew :e2e-driver:installDebug :e2e-driver:installDebugAndroidTest

"${ADB_DEVICE[@]}" logcat -c
restore_davi_accessibility

# dumpsys의 Bound 표시는 서비스의 onServiceConnected 콜백보다 먼저 바뀔 수 있다. 실제 callback이
# 실행되기 전에 instrumentation을 시작하면 START 세션을 받을 listener가 없어 첫 결정을 영영 못 받는다.
CONNECTED=false
for _ in {1..30}; do
    if "${ADB_DEVICE[@]}" logcat -d -s GuideSvc:I '*:S' | grep -q 'connected server='; then
        CONNECTED=true
        break
    fi
    sleep 1
done
if [[ "$CONNECTED" != true ]]; then
    printf 'Davi 접근성 서비스의 실제 연결 callback을 30초 안에 확인하지 못했습니다.\n' >&2
    exit 2
fi

ENABLED_SERVICES="$("${ADB_DEVICE[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$ENABLED_SERVICES" != *"$SERVICE"* ]]; then
    printf 'Davi 접근성 서비스를 다시 바인딩하지 못했습니다. 설정에서 직접 켜세요.\n' >&2
    exit 2
fi
ACCESSIBILITY_STATE="$("${ADB_DEVICE[@]}" shell dumpsys accessibility)"
if ! grep -q 'Bound services:.*com\.davi\.helper' <<<"$ACCESSIBILITY_STATE" ||
    grep -q 'Crashed services:.*com\.davi\.helper' <<<"$ACCESSIBILITY_STATE"; then
    printf 'Davi 접근성 서비스가 bound 상태가 아닙니다. 설정에서 껐다 켠 뒤 다시 실행하세요.\n' >&2
    exit 2
fi
if ! "${ADB_DEVICE[@]}" shell appops get com.davi.helper SYSTEM_ALERT_WINDOW | grep -q 'allow'; then
    printf 'Davi의 다른 앱 위에 표시 권한을 기기 설정에서 허용하세요.\n' >&2
    exit 2
fi

set +e
INSTRUMENTATION_ARGS=(-w -r -e davi.liveGemini true -e class "$TEST_CLASS")
if [[ "$DETAILED_REPORT" == false ]]; then
    INSTRUMENTATION_ARGS+=(-e davi.detailedReport false)
fi
"${ADB_DEVICE[@]}" shell am instrument "${INSTRUMENTATION_ARGS[@]}" \
    "$RUNNER" | tee "$INSTRUMENTATION_RESULT"
# pipefail이 켜져 있어 adb 또는 tee 어느 쪽이든 실패하면 이 status가 실패다. Bash 3의
# PIPESTATUS 배열과 nounset 조합은 빈 배열로 평가될 수 있어 직전 pipeline status를 직접 쓴다.
INSTRUMENTATION_SHELL_STATUS="$?"
set -e

print_safe_logs() {
    printf '\n비식별 실기기 검증 로그:\n'
    "${ADB_DEVICE[@]}" logcat -d -s GuideSvc:I DaviE2e:I '*:S' |
        sed -E 's/(dogfoodDeviceId|device_id)=[^[:space:]]+/\1=[redacted]/g' > "$SAFE_LOG_RESULT"
    sed -n '/GuideSvc\|DaviE2e/p' "$SAFE_LOG_RESULT"
}

print_safe_logs
if [[ "$DETAILED_REPORT" == true ]]; then
    if "${ADB_DEVICE[@]}" pull "$DEVICE_PRIVATE_TRACE" "$PRIVATE_TRACE_RESULT" >/dev/null 2>&1; then
        PRIVATE_TRACE_AVAILABLE=true
    else
        printf '상세 분석 trace를 가져오지 못했습니다. 기본 비식별 보고서는 계속 생성합니다.\n' >&2
    fi
fi
python3 "$REPORT_RENDERER" \
    --instrumentation-result "$INSTRUMENTATION_RESULT" \
    --safe-log "$SAFE_LOG_RESULT" \
    --output "$REPORT_PATH" \
    --model "$MODEL" \
    --device-model "$DEVICE_MODEL" \
    --android-version "$ANDROID_VERSION" \
    --commit "$GIT_COMMIT" \
    --started-at "$RUN_STARTED_AT" \
    --scenario "$SCENARIO" \
    --scenario-description "$SCENARIO_DESCRIPTION" \
    --test-class "$TEST_CLASS" \
    --shell-status "$INSTRUMENTATION_SHELL_STATUS"
cp "$REPORT_PATH" "$LATEST_REPORT"
printf '\n결과 문서: %s\n' "$REPORT_PATH"
printf '최신 결과: %s\n' "$LATEST_REPORT"
if [[ "$DETAILED_REPORT" == true && "$PRIVATE_TRACE_AVAILABLE" == true ]]; then
    python3 "$PRIVATE_REPORT_RENDERER" \
        --trace "$PRIVATE_TRACE_RESULT" \
        --output "$PRIVATE_REPORT_PATH" \
        --model "$MODEL" \
        --device-model "$DEVICE_MODEL" \
        --android-version "$ANDROID_VERSION" \
        --commit "$GIT_COMMIT" \
        --started-at "$RUN_STARTED_AT" \
        --scenario "$SCENARIO"
    cp "$PRIVATE_REPORT_PATH" "$LATEST_PRIVATE_REPORT"
    printf '상세 분석 문서(로컬 전용): %s\n' "$PRIVATE_REPORT_PATH"
    printf '최신 상세 분석(로컬 전용): %s\n' "$LATEST_PRIVATE_REPORT"
fi

INSTRUMENTATION_NORMALIZED="$(tr -d '\r' < "$INSTRUMENTATION_RESULT")"
if [[ "$INSTRUMENTATION_SHELL_STATUS" -ne 0 ]] ||
    ! grep -q '^INSTRUMENTATION_CODE: -1$' <<<"$INSTRUMENTATION_NORMALIZED" ||
    ! grep -Eq '^OK \([0-9]+ tests?\)$' <<<"$INSTRUMENTATION_NORMALIZED" ||
    grep -q '^FAILURES!!!' <<<"$INSTRUMENTATION_NORMALIZED"; then
    printf '실기기 instrumentation이 실패했습니다. 화면/안내 원문은 출력하지 않았습니다.\n' >&2
    exit 1
fi
