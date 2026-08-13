# 실제 Gemini 실기기 E2E 검증 결과

이 문서는 #124에서 삼성 시계 알람 시나리오용 UIAutomator driver를 구현하면서 실제 SM-S921N에서
확인한 결과를 기록한다. 실행 방법과 판정 기준은 [`DEVICE-E2E.md`](DEVICE-E2E.md)에 있다.

## 검증 환경

| 항목 | 값 |
|---|---|
| 실행일 | 2026-08-13 (KST) |
| 기기 | Samsung SM-S921N / Android 15 |
| 대상 앱 | Samsung Clock 12.4.10.10 |
| 서버 | 로컬 개발 서버, `DAVI_USE_STUB_LLM=false` |
| 모델 | `gemini-3.5-flash-lite` |
| 시나리오 | 테스트 전용 이름과 목표 시간을 가진 알람 생성 후 자동 삭제 |

기기 serial, API 키, 화면 트리, 개인 알람 label, Gemini 응답 원문과 안내 문장은 기록하지 않았다.

## 구현 검증 결과

| 검증 항목 | 결과 | 확인 내용 |
|---|---|---|
| 앱·driver·test APK 빌드/설치 | 통과 | 제품 앱과 별도 instrumentation 프로세스로 실행 |
| 접근성 서비스 공존 | 통과 | 실행 중 Davi `Bound`, `Crashed services` 비어 있음 |
| 실제 Gemini 호출 | 통과 | stub이 아닌 모델 schema 요청과 HTTP 200 응답 확인 |
| 안내 기반 조작 | 통과 | 정답 selector가 아니라 Gemini action과 target bounds로 외부 앱 조작 |
| 편집 화면 진입 | 통과 | 알람 목록에서 알람 편집 화면까지 실제 화면 전환 확인 |
| 시나리오 최종 완료 | **실패** | 최대 18단계 안에 목표 알람 생성과 검증된 `done`에 도달하지 못함 |
| 실패 시 데이터 정리 | 통과 | 실행 뒤 `DAVI_E2E_TEST` 알람이 남아 있지 않음을 별도 확인 |
| API 키 없는 회귀 빌드 | 통과 | debug 앱, driver test APK와 JVM 테스트 컴파일 성공 |
| release 격리 | 통과 | release APK에 E2E receiver와 signature 권한이 없음을 확인 |

## 실제 모델 실행에서 발견한 결과

### 실행 1 — 문장과 구조화 action 불일치

첫 화면의 실제 Gemini 요청은 성공했지만, 안내 문장에 구조화 action과 맞는 최소 동작 표현이 없어
`instruction_action_mismatch` 품질 게이트에서 중단됐다. 문장 exact-match가 아니라 action별 한국어
동작 어휘의 존재만 검사한 결과다.

### 실행 2 — 단계 상한 도달

재실행에서는 알람 추가 대상을 선택해 편집 화면까지 진입했고 실제 Gemini 응답과 UIAutomator 조작이
이어졌다. 그러나 허용된 최대 18단계 안에 목표 시간·테스트 이름·저장·검증된 완료까지 도달하지 못해
`step_limit`으로 실패했다. 이 코드는 API 사용량 제한이 아니라 **시나리오 진행 단계 상한**이다.

따라서 현재 결론은 다음과 같다.

- 실제 Gemini를 포함한 닫힌 루프 E2E 인프라는 동작한다.
- 테스트가 현재 모델의 안내 불일치와 반복/미진행을 실제 품질 실패로 검출한다.
- 삼성 시계 알람 생성 시나리오가 성공한다고 아직 주장할 수 없다.
- 테스트가 Gemini 결정을 정답 selector로 보정하지 않았기 때문에 이 실패는 그대로 보존해야 한다.

## 이후 실행 결과 확인

`./scripts/run-samsung-clock-e2e.sh`를 실행하면 매번 다음 로컬 문서가 새로 생성된다.

```text
app-android/build/reports/device-e2e/latest.md
```

보고서에는 다음 내용만 포함된다.

- 통과/실패와 구조화 실패 코드
- 실행 시각, 모델명, 기기 모델·Android 버전, Git commit
- 관측한 단계 수와 서버 요청 시작 수
- 단계별 `action`, target bounds 존재 여부, 완료 검증 여부
- 테스트 전용 데이터 정리 성공 여부

화면/안내 원문과 개인·인증 정보는 보고서에 포함하지 않는다. `build/` 아래의 자동 결과는 로컬
산출물이므로 Git에는 커밋되지 않는다.
