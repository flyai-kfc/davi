# davi

> 시니어가 스마트폰의 다른 앱을 사용할 때, 현재 화면을 읽고 **다음 한 가지 조작**을 안내하는 Android 접근성 기반 도우미입니다.

**현재 목표 사이클: 클로즈드 베타.** 전환 관문과 미결은 제품 SPEC §11에서 확인합니다. Davi는 화면 위 안내와 강조를 제공하고, 실제 조작과 최종 결정은 사용자가 합니다. 입력의 유일한 예외는 사용자가 「바로 입력」 버튼을 눌렀을 때 안내문에 적힌 값을 입력칸에 채우는 것입니다. 검색 실행·결제·전송은 대신하지 않습니다.

## 어떻게 동작하나요?

1. 사용자가 음성으로 목적을 말합니다. 개발용 debug 화면에는 텍스트 입력도 있습니다.
2. 정보가 부족하면 최대 두 개의 질문으로 목표를 구체화합니다.
3. Android 앱이 현재 화면의 접근성 트리를 읽고 기기에서 1차 마스킹합니다.
4. 서버가 구조화된 다음 행동 하나를 만들고, 앱이 화면 위에 표시·낭독합니다.
5. 사용자가 직접 조작하면 앱이 새 화면을 읽어 안내를 이어갑니다.

```text
사용자 발화
    │
    ▼
Android 앱 ── 마스킹된 화면·목표 ──▶ 서버 ── 다음 행동 하나 ──▶ Android 오버레이
    │                                                                  │
    └────────────────────── 사용자가 직접 조작 ────────────────────────┘
```

## 저장소 구성

### Android 앱

접근성 서비스, 화면 트리, 오버레이, 음성 목표 UI를 담당합니다.

- [빌드·설치·실기기 실행 안내](https://github.com/flyai-kfc/davi/blob/main/app-android/README.md)

### 서버

목표 구체화, LLM 호출, 가드레일, 완료 판정 API를 담당합니다.

- [서버 실행·환경 설정](https://github.com/flyai-kfc/davi/blob/main/server/README.md)

### 평가

`eval/`은 화면 fixture 회귀와 결정론적 가드레일을 검증하고, `eval/pingpong/`은 실제 모델을 사용한 목표 구체화 A/B 평가를 담당합니다.

- [fixture 기반 eval 실행](https://github.com/flyai-kfc/davi/blob/main/eval/README.md)
- [핑퐁 eval 실행](https://github.com/flyai-kfc/davi/blob/main/eval/pingpong/README.md)

### 문서와 인프라

- [제품·디자인·개인정보·배포 문서](https://github.com/flyai-kfc/davi/tree/main/docs)
- [Azure release 서버 배포](https://github.com/flyai-kfc/davi/blob/main/docs/deployment/azure-release-server.md)

## 시작하기

상세 설치 절차는 각 스택 README에만 둡니다. 같은 명령을 여기 복사하지 않아 문서가 어긋나는 것을 막습니다.

- Android debug 앱 설치와 접근성 서비스 설정: [app-android README](https://github.com/flyai-kfc/davi/blob/main/app-android/README.md)
- 키 없이 스텁 서버를 로컬에서 실행하거나 실제 모델을 연결: [server README](https://github.com/flyai-kfc/davi/blob/main/server/README.md)
- API 키 없이 화면 fixture 회귀 실행: [eval README](https://github.com/flyai-kfc/davi/blob/main/eval/README.md)
- 목표 구체화 프롬프트를 실제 모델로 A/B 평가: [pingpong eval README](https://github.com/flyai-kfc/davi/blob/main/eval/pingpong/README.md)

## 계약과 검증

- HTTP JSON 계약의 정본은 [공개 Pydantic 모델](https://github.com/flyai-kfc/davi/blob/main/server/app/schemas.py)입니다.
- Android는 [Contract.kt](https://github.com/flyai-kfc/davi/blob/main/app-android/app/src/main/java/com/davi/helper/Contract.kt)에 대응 모델을 유지합니다.
- [계약 드리프트 테스트](https://github.com/flyai-kfc/davi/blob/main/server/tests/test_contract_drift.py)가 enum과 wire 필드의 드리프트를 검사합니다.
- `eval/`은 익명화된 화면 fixture의 입력 보존·개인정보 검사·가드레일 회귀를 API 키 없이 검증합니다.

계약을 바꾸기 전에는 [서버 API 계약 결정](https://github.com/flyai-kfc/davi/blob/main/server/CLAUDE.md)을 확인하고, 앱·서버·eval에 미치는 영향을 PR에 함께 적어야 합니다.

## 개인정보와 안전 경계

- API 키와 접근 토큰은 앱 APK나 저장소에 넣지 않습니다.
- 화면 label·음성 발화·프롬프트·모델 원문 응답은 운영 로그에 남기지 않습니다.
- fixture는 사람이 익명화한 뒤 자동 PII 검사를 거칩니다.
- 클로즈드 베타 release 참여자의 `device_id`와 요청·응답 원문은 도그푸딩 raw 저장 대상이 아닙니다.
- 다른 앱이 접근성 트리에 노출하지 않은 화면 밖 데이터는 읽지 않습니다.
- 실제 전송·구매·삭제처럼 되돌리기 어려운 행동은 제품 기능이나 자동 테스트에서 별도 안전 경계 없이는 수행하지 않습니다.

자세한 원칙은 [개인정보 처리 방침](https://github.com/flyai-kfc/davi/blob/main/docs/PRIVACY-POLICY.md)과 [제품 SPEC](https://github.com/flyai-kfc/davi/blob/main/SPEC.md)을 참고하세요.

## 현재 범위

내부 도그푸딩에서 확인한 닫힌 안내 루프를 바탕으로, 승인된 클로즈드 베타 참여자가 실제 기기에서 안내 품질과 실패·중단 경험을 검증하는 것을 목표로 합니다. 현재 배포 경로는 Play 내부 테스트(#486)이며, Play 비공개 테스트·프로덕션 트랙 전환을 뜻하지 않습니다. release 실기기 관통과 고지·운영 관문이 모두 완료됐다고 주장하지 않으며, 완료·보류 상태는 SPEC §11과 PR #502에 구분합니다.

베타의 참여 범위, 개인정보 처리, 배포·운영 조건과 확정된 제품 결정은 [제품 SPEC](https://github.com/flyai-kfc/davi/blob/main/SPEC.md)과 [개인정보 처리 방침](https://github.com/flyai-kfc/davi/blob/main/docs/PRIVACY-POLICY.md)을 기준으로 관리합니다.

## 기여

`main`에 직접 푸시하지 않습니다. 기능 브랜치 → PR → 리뷰 → CI 확인 순서를 따릅니다.

- [기여·브랜치·PR·CI 규칙](https://github.com/flyai-kfc/davi/blob/main/CONTRIBUTING.md)
- [프로젝트 작업 규칙](https://github.com/flyai-kfc/davi/blob/main/CLAUDE.md)
- [이전 PoC의 인수인계와 교훈](https://github.com/flyai-kfc/davi/blob/main/HANDOFF.md)
