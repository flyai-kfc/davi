#!/usr/bin/env bash
set -euo pipefail

# macOS에서 debug 앱을 빌드·설치하고 접근성 서비스를 복구한다(#72).
# 실패는 0이 아닌 exit code로 알린다. 나중의 Bash 호출자는 이 계약을 `$?`/`set -e`로 받는다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/accessibility.sh
. "$SCRIPT_DIR/lib/accessibility.sh"
SERVICE_CLASS="com.davi.helper.GuideAccessibilityService"
FALLBACK_PACKAGE="com.davi.helper.debug"

STATUS=false
SKIP_BUILD=false
SKIP_ACCESSIBILITY=false
CLEAR_LOG=false
SERIAL=""
PACKAGE_NAME=""
ADB=""
SDK_ROOT=""
TARGET_SERIAL=""
PACKAGE=""
SERVICE=""
CONNECTED_SERIALS=()
ADB_DEVICE=()
ACCESSIBILITY_RESTORE_NEEDED=false
ACCESSIBILITY_RESTORE_ORIGINAL=""

usage() {
    cat <<'EOF'
사용법: ./scripts/dev-install.sh [옵션]

옵션:
  --status                 아무것도 바꾸지 않고 상태만 출력
  --skip-build             빌드·설치 없이 접근성만 복구
  --a11y-only              --skip-build 별칭
  --skip-accessibility     접근성 서비스를 건드리지 않음
  --clear-log              끝나고 logcat 버퍼 비우기
  --serial SERIAL          여러 기기 중 대상 지정(ANDROID_SERIAL도 지원)
  --package-name PACKAGE   debug 패키지명 직접 지정
  -h, --help               도움말 출력

실패는 0이 아닌 exit code로 반환한다. --status는 기기 없음·복수도 진단으로 출력하고 0을 반환한다.
EOF
}

die() {
    printf '오류: %s\n' "$*" >&2
    exit 1
}

warn() {
    printf '경고: %s\n' "$*" >&2
}

need_value() {
    local option="$1"
    local count="$2"
    if [[ "$count" -lt 2 ]]; then
        printf '%s 값이 필요합니다.\n' "$option" >&2
        usage >&2
        exit 2
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --status) STATUS=true; shift ;;
        --skip-build|--a11y-only) SKIP_BUILD=true; shift ;;
        --skip-accessibility) SKIP_ACCESSIBILITY=true; shift ;;
        --clear-log) CLEAR_LOG=true; shift ;;
        --serial)
            need_value "$1" "$#"
            SERIAL="$2"
            shift 2
            ;;
        --package-name)
            need_value "$1" "$#"
            PACKAGE_NAME="$2"
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *)
            printf '알 수 없는 옵션: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

trim() {
    sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

sdk_dir_from_local_properties() {
    local properties="$APP_DIR/local.properties"
    local raw
    [[ -f "$properties" ]] || return 1
    raw="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$properties" | head -n 1)"
    [[ -n "$raw" ]] || return 1
    # Gradle properties에서 흔히 쓰는 이스케이프만 되돌린다. macOS 경로의 `/`는 그대로다.
    printf '%s\n' "$raw" | sed 's/\\:/:/g;s/\\ / /g;s/\\\\/\\/g' | trim
}

resolve_adb() {
    local candidate sdk local_sdk user_home
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [[ -n "$sdk" ]] || continue
        candidate="$sdk/platform-tools/adb"
        if [[ -x "$candidate" ]]; then
            SDK_ROOT="$sdk"
            ADB="$candidate"
            return
        fi
    done

    local_sdk="$(sdk_dir_from_local_properties || true)"
    if [[ -n "$local_sdk" && -x "$local_sdk/platform-tools/adb" ]]; then
        SDK_ROOT="$local_sdk"
        ADB="$local_sdk/platform-tools/adb"
        return
    fi

    user_home="${HOME:-}"
    if [[ -n "$user_home" && -x "$user_home/Library/Android/sdk/platform-tools/adb" ]]; then
        SDK_ROOT="$user_home/Library/Android/sdk"
        ADB="$SDK_ROOT/platform-tools/adb"
        return
    fi

    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
        return
    fi
    die 'adb를 찾지 못했습니다. ANDROID_HOME/ANDROID_SDK_ROOT, local.properties의 sdk.dir 또는 PATH를 확인하세요.'
}

resolve_java_home() {
    local user_home candidate
    if [[ -n "${JAVA_HOME:-}" ]]; then
        [[ -x "$JAVA_HOME/bin/java" ]] || die "JAVA_HOME의 java를 실행할 수 없습니다: $JAVA_HOME/bin/java"
        return
    fi

    user_home="${HOME:-}"
    for candidate in \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$user_home/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            return
        fi
    done
    die 'JDK를 찾지 못했습니다. JAVA_HOME을 지정하거나 /Applications에 Android Studio를 설치하세요.'
}

capture_connected_serials() {
    local output serial state rest
    CONNECTED_SERIALS=()
    if ! output="$("$ADB" devices)"; then
        die 'adb devices 실행에 실패했습니다. adb 서버와 기기 연결을 확인하세요.'
    fi
    while IFS=$'\t' read -r serial state rest; do
        if [[ "$state" == "device" ]]; then
            CONNECTED_SERIALS[${#CONNECTED_SERIALS[@]}]="$serial"
        fi
    done < <(printf '%s\n' "$output")
}

joined_serials() {
    local result="" item count="${#CONNECTED_SERIALS[@]}"
    # macOS Bash 3.2 + `set -u`에서는 빈 배열의 `[@]` 전개가 unbound variable이다. 현재 호출부는
    # 원소 수를 확인하지만, 함수 자체도 빈 배열을 안전하게 받아 새 호출부가 같은 함정을 밟지 않게 한다.
    if [[ "$count" -eq 0 ]]; then
        printf '\n'
        return
    fi
    for item in "${CONNECTED_SERIALS[@]}"; do
        result="${result:+$result, }$item"
    done
    printf '%s\n' "$result"
}

resolve_serial() {
    local allow_none="$1"
    local requested="${SERIAL:-${ANDROID_SERIAL:-}}"
    local item found=false count="${#CONNECTED_SERIALS[@]}"

    if [[ -n "$requested" ]]; then
        # macOS 기본 Bash 3.2 + `set -u`에서는 빈 배열의 `[@]` 전개 자체가 unbound variable이다.
        # USB가 빠진 채 ANDROID_SERIAL이 남은 흔한 조합이므로, 원소가 있을 때만 전개한다.
        if [[ "$count" -gt 0 ]]; then
            for item in "${CONNECTED_SERIALS[@]}"; do
                [[ "$item" == "$requested" ]] && found=true
            done
        fi
        if [[ "$found" != true ]]; then
            die "지정한 기기($requested)가 연결 상태가 아닙니다. 연결된 기기: $(if [[ "$count" -gt 0 ]]; then joined_serials; else printf '없음'; fi)"
        fi
        printf '%s\n' "$requested"
        return
    fi
    if [[ "$count" -eq 1 ]]; then
        printf '%s\n' "${CONNECTED_SERIALS[0]}"
    elif [[ "$count" -eq 0 ]]; then
        [[ "$allow_none" == true ]] && return
        die '연결되고 adb 승인된 기기가 없습니다. adb devices로 확인하세요.'
    else
        die "기기가 여러 대 연결되어 있습니다($(joined_serials)). --serial 또는 ANDROID_SERIAL로 대상을 지정하세요."
    fi
}

adb_capture() {
    local what="$1" output
    shift
    if ! output="$("${ADB_DEVICE[@]}" "$@")"; then
        die "$what 실패"
    fi
    printf '%s\n' "$output" | tr -d '\r'
}

adb_checked() {
    local what="$1"
    shift
    if ! "${ADB_DEVICE[@]}" "$@"; then
        die "$what 실패"
    fi
}

installed_service_packages() {
    local state
    [[ -n "$TARGET_SERIAL" ]] || return 0
    state="$(adb_capture '접근성 서비스 패키지 조회' shell dumpsys accessibility)" || return
    printf '%s\n' "$state" | awk -v service_class="$SERVICE_CLASS" '
        {
            line = $0
            while (match(line, /[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+\/[A-Za-z0-9_.]+/)) {
                component = substr(line, RSTART, RLENGTH)
                split(component, parts, "/")
                package_name = parts[1]
                class_name = parts[2]
                qualified = substr(class_name, 1, 1) == "." ? package_name class_name : class_name
                if (qualified == service_class && !seen[package_name]++) print package_name
                line = substr(line, RSTART + RLENGTH)
            }
        }
    '
}

metadata_package_name() {
    local metadata="$APP_DIR/app/build/outputs/apk/debug/output-metadata.json"
    [[ -f "$metadata" ]] || return 1
    sed -n 's/.*"applicationId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$metadata" |
        head -n 1
}

resolve_package_name() {
    local installed="" installed_output count=0 item metadata
    if [[ -n "$PACKAGE_NAME" ]]; then
        PACKAGE="$PACKAGE_NAME"
        SERVICE="$PACKAGE/$SERVICE_CLASS"
        return
    fi

    installed_output="$(installed_service_packages)" || return
    while IFS= read -r item; do
        [[ -n "$item" ]] || continue
        installed="${installed:+$installed$'\n'}$item"
        count=$((count + 1))
    done < <(printf '%s\n' "$installed_output")
    if [[ "$count" -eq 1 ]]; then
        PACKAGE="$installed"
        SERVICE="$PACKAGE/$SERVICE_CLASS"
        return
    fi
    if [[ "$count" -gt 1 && ( "$SKIP_BUILD" == true || "$STATUS" == true ) ]]; then
        warn "다비 접근성 서비스를 가진 패키지가 여럿입니다($(printf '%s' "$installed" | tr '\n' ',' )). --package-name으로 지정하세요."
    fi

    metadata="$(metadata_package_name || true)"
    PACKAGE="${metadata:-$FALLBACK_PACKAGE}"
    SERVICE="$PACKAGE/$SERVICE_CLASS"
}

enabled_services() {
    local raw
    raw="$(adb_capture '접근성 목록 조회' shell settings get secure enabled_accessibility_services)" || return
    raw="$(printf '%s\n' "$raw" | trim)"
    [[ "$raw" == "null" ]] && raw=""
    printf '%s\n' "$raw"
}

accessibility_global() {
    local raw
    raw="$(adb_capture '접근성 전역 상태 조회' shell settings get secure accessibility_enabled)" || return
    printf '%s\n' "$raw" | trim
}

section_has_service() {
    local section="$1" package_name="$2" service_class="$3" short
    case "$section" in *"$package_name/$service_class"*) return 0 ;; esac
    case "$service_class" in
        "$package_name".*)
            short="$package_name/${service_class#$package_name}"
            case "$section" in *"$short"*) return 0 ;; esac
            ;;
    esac
    return 1
}

service_bound_state() {
    local state crashed bound
    state="$(adb_capture '접근성 바인딩 상태 조회' shell dumpsys accessibility)" || return
    crashed="$(printf '%s\n' "$state" | dumpsys_section 'Crashed services')"
    bound="$(printf '%s\n' "$state" | dumpsys_section 'Bound services')"
    if section_has_service "$crashed" "$PACKAGE" "$SERVICE_CLASS"; then
        printf 'crashed\n'
    elif section_has_service "$bound" "$PACKAGE" "$SERVICE_CLASS"; then
        printf 'bound\n'
    else
        printf 'unbound\n'
    fi
}

service_is_listed() {
    local services="$1"
    case ":$services:" in *":$SERVICE:"*) return 0 ;; esac
    return 1
}

package_is_installed() {
    local lines line
    lines="$(adb_capture '설치 패키지 조회' shell pm list packages "$PACKAGE")" || return 2
    while IFS= read -r line; do
        line="${line#package:}"
        [[ "$(printf '%s' "$line" | trim)" == "$PACKAGE" ]] && return 0
    done < <(printf '%s\n' "$lines")
    return 1
}

installed_version_name() {
    local state
    state="$(adb_capture '앱 버전 조회' shell dumpsys package "$PACKAGE")" || return
    printf '%s\n' "$state" |
        sed -n 's/^[[:space:]]*versionName=\([^[:space:]]*\).*/\1/p' | head -n 1
}

print_status() {
    local model extra version services global_on bound package_status
    if [[ -z "$TARGET_SERIAL" ]]; then
        if [[ -n "${SERIAL_PROBLEM:-}" ]]; then
            printf '기기      : %s\n' "$SERIAL_PROBLEM"
        else
            printf '기기      : 없음 — 연결과 adb 승인을 확인하세요\n'
        fi
        printf '패키지    : 확인 불가(대상 기기 없음)\n'
        printf '접근성    : 확인 불가(대상 기기 없음)\n'
        return
    fi

    model="$(adb_capture '기기 모델 조회' shell getprop ro.product.model)" || return
    model="$(printf '%s\n' "$model" | trim)"
    extra=""
    [[ "${#CONNECTED_SERIALS[@]}" -gt 1 ]] && extra=" (연결 ${#CONNECTED_SERIALS[@]}대 중 대상)"
    printf '기기      : %s [%s]%s\n' "$model" "$TARGET_SERIAL" "$extra"

    if package_is_installed; then
        :
    else
        package_status="$?"
        [[ "$package_status" -eq 2 ]] && return 2
        printf '패키지    : %s 설치되지 않음 — dev-install.sh로 설치하세요\n' "$PACKAGE"
        printf '접근성    : 확인 생략(미설치)\n'
        return
    fi
    version="$(installed_version_name)" || return
    printf '패키지    : %s %s 설치됨\n' "$PACKAGE" "${version:-(버전 확인 불가)}"

    services="$(enabled_services)" || return
    global_on="$(accessibility_global)" || return
    bound="$(service_bound_state)" || return
    case "$bound" in
        bound)
            if [[ "$global_on" != "1" ]]; then
                printf '접근성    : 바인딩은 있으나 전역 스위치가 꺼짐 — --a11y-only로 복구\n'
            elif service_is_listed "$services"; then
                printf '접근성    : 목록 등록됨, 바인딩 살아 있음\n'
            else
                printf '접근성    : 바인딩은 있으나 목록에 없음 — 재설치 시 꺼집니다\n'
            fi
            ;;
        crashed) printf '접근성    : 서비스가 크래시 상태 — 로그(GuideSvc) 확인 후 --a11y-only로 복구\n' ;;
        *)
            if service_is_listed "$services"; then
                printf '접근성    : 목록엔 있으나 바인딩 없음 — --a11y-only로 복구\n'
            else
                printf '접근성    : 꺼져 있음 — --a11y-only로 복구\n'
            fi
            ;;
    esac
}

restore_accessibility_state() {
    local original="$1" ok=true
    if [[ -n "$original" ]]; then
        "${ADB_DEVICE[@]}" shell settings put secure enabled_accessibility_services "$original" >/dev/null || ok=false
    else
        "${ADB_DEVICE[@]}" shell settings delete secure enabled_accessibility_services >/dev/null || ok=false
    fi
    "${ADB_DEVICE[@]}" shell settings put secure accessibility_enabled 1 >/dev/null || ok=false
    if [[ "$ok" == true ]]; then
        warn '접근성 복구가 중단되어 목록을 진입 시점으로 되돌리고 전역 스위치를 켰습니다.'
    else
        warn '접근성 복구가 중단됐고 되돌리기도 실패했습니다. 기기 설정에서 접근성을 직접 확인하세요.'
    fi
}

restore_accessibility_on_exit() {
    local status="$?"
    if [[ "$ACCESSIBILITY_RESTORE_NEEDED" == true ]]; then
        ACCESSIBILITY_RESTORE_NEEDED=false
        restore_accessibility_state "$ACCESSIBILITY_RESTORE_ORIGINAL"
    fi
    trap - EXIT
    exit "$status"
}

trap restore_accessibility_on_exit EXIT

put_or_restore() {
    local original="$1" what="$2"
    shift 2
    if ! "${ADB_DEVICE[@]}" "$@"; then
        ACCESSIBILITY_RESTORE_NEEDED=false
        restore_accessibility_state "$original"
        die "$what 실패"
    fi
}

enable_accessibility() {
    local current without="" item next bound global_on
    current="$(enabled_services)" || return
    bound="$(service_bound_state)" || return
    global_on="$(accessibility_global)" || return
    if service_is_listed "$current" && [[ "$bound" == "bound" ]] && [[ "$global_on" == "1" ]]; then
        printf '  이미 켜져 있음\n'
        return
    fi

    while IFS= read -r item; do
        [[ -n "$item" && "$item" != "$SERVICE" ]] || continue
        without="${without:+$without:}$item"
    # 마지막 항목에도 줄바꿈을 줘야 서비스가 하나뿐인 목록을 Bash `read`가 버리지 않는다.
    done < <(printf '%s\n' "$current" | tr ':' '\n')
    next="${without:+$without:}$SERVICE"

    ACCESSIBILITY_RESTORE_ORIGINAL="$current"
    ACCESSIBILITY_RESTORE_NEEDED=true
    put_or_restore "$current" '접근성 전역 끄기' shell settings put secure accessibility_enabled 0
    if [[ -n "$without" ]]; then
        put_or_restore "$current" '접근성 목록 비우기' shell settings put secure enabled_accessibility_services "$without"
    else
        put_or_restore "$current" '접근성 목록 비우기' shell settings delete secure enabled_accessibility_services
    fi
    put_or_restore "$current" '접근성 목록 설정' shell settings put secure enabled_accessibility_services "$next"
    put_or_restore "$current" '접근성 전역 켜기' shell settings put secure accessibility_enabled 1
    ACCESSIBILITY_RESTORE_NEEDED=false

    sleep 0.8
    bound="$(service_bound_state)" || return
    if [[ "$bound" == "bound" ]]; then
        printf '  켬: %s\n' "$SERVICE"
    else
        warn "접근성 서비스가 바인딩되지 않았습니다(상태: $bound). 설정 앱에서 직접 켜세요."
        if ! "${ADB_DEVICE[@]}" shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null; then
            warn '접근성 설정 화면을 열지 못했습니다.'
        fi
    fi
}

resolve_adb
capture_connected_serials
SERIAL_PROBLEM=""
if [[ "$STATUS" == true ]]; then
    if ! SERIAL_ERROR_FILE="$(mktemp -t davi-dev-install-serial.XXXXXX)"; then
        die '상태 진단용 임시 파일을 만들지 못했습니다.'
    fi
    if ! TARGET_SERIAL="$(resolve_serial true 2>"$SERIAL_ERROR_FILE")"; then
        SERIAL_PROBLEM="$(sed 's/^오류: //' "$SERIAL_ERROR_FILE" | trim)"
        TARGET_SERIAL=""
    fi
    rm -f "$SERIAL_ERROR_FILE"
else
    TARGET_SERIAL="$(resolve_serial false)"
fi

if [[ -n "$TARGET_SERIAL" ]]; then
    ADB_DEVICE=("$ADB" -s "$TARGET_SERIAL")
else
    ADB_DEVICE=("$ADB")
fi
resolve_package_name

if [[ "$STATUS" == true ]]; then
    print_status
    exit 0
fi

if [[ "$SKIP_BUILD" == true ]]; then
    printf '== 빌드 건너뜀 (--skip-build / --a11y-only) ==\n'
else
    resolve_java_home
    [[ -x "$APP_DIR/gradlew" ]] || die "Gradle wrapper를 실행할 수 없습니다: $APP_DIR/gradlew"
    if [[ -n "$SDK_ROOT" ]]; then
        export ANDROID_HOME="$SDK_ROOT"
        export ANDROID_SDK_ROOT="$SDK_ROOT"
    fi
    printf '== 빌드 + 설치 (%s) ==\n' "$TARGET_SERIAL"
    (
        cd "$APP_DIR"
        ANDROID_SERIAL="$TARGET_SERIAL" ./gradlew installDebug
    )

    previous_package="$PACKAGE"
    resolve_package_name
    if [[ "$PACKAGE" != "$previous_package" ]]; then
        printf '  대상 패키지: %s\n' "$PACKAGE"
    fi
fi

if [[ "$SKIP_ACCESSIBILITY" != true ]]; then
    printf '== 접근성 서비스 활성화 ==\n'
    enable_accessibility
fi

if [[ "$CLEAR_LOG" == true ]]; then
    adb_checked 'adb logcat -c' logcat -c
    printf '== logcat 버퍼 비움 ==\n'
fi

printf '\n완료. 로그: adb -s %s logcat -s GuideSvc\n' "$TARGET_SERIAL"
