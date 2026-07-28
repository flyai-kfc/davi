# CLAUDE.md — server (추론 오케스트레이터)

Python 3.12 / FastAPI. 앱이 보낸 [마스킹된 화면 + 목표]로 **다음 안내 하나**를 정해 돌려준다.
공통 규칙·도메인 용어는 루트 `../CLAUDE.md`. 방향은 `../SPEC.md`.

## 이 폴더의 책임
- **2차 마스킹 그물** — 앱 1차 마스킹이 놓친 패턴 방어. 원격 업데이트 가능(앱 재배포 불필요).
- **프롬프트 구성 + 가드레일** — 앱별/버전별 지식과 일반 정책을 여기 둔다. 가드는 **단일 결정 지점**으로 통합(가드끼리 덮어써 불안정했던 §5-4 전례 회피).
- **모델 호출** — `gemini-3.5-flash-lite`. `MODEL` 상수 한 줄로 교체 가능.
- **완료 검증** — `expected_result`/`evidence`를 써내게 하고 비교해 조기 종료 방지.
- **에러 분류** — QUOTA/NETWORK/AUTH/SERVER/PARSE/BAD_REQUEST. 앱이 사용자에게 다르게 안내하도록.

## 추론 아키텍처 (§5-3: 계획→실행→검증 3단으로 재설계)
PoC의 단일 스텝 반응형 + 가드레일 누적 구조를 넘어선다.
1. **계획(sub-task)** — 목표를 하위 태스크로. 선행 조건 처리 가능(예: 앱 없으면 먼저 설치).
2. **실행** — 현재 화면에서 다음 한 걸음.
3. **명시적 검증** — 완료 판정을 별도 단계로.

## 실행
- **`py -3.12` 고정.** 기본 python이 3.8이면 litellm(3.11+ 필요)에서 혼란스러운 에러(§10).
- 로컬 uvicorn + 터널(ngrok/Cloudflare)로 실기기가 접속(`../SPEC.md` §7).
- API 키는 **환경변수**. 커밋 금지. `.env`는 gitignore됨.

## Gemini 구조화 출력 함정 (`HANDOFF.md` §10 — 실측)
- `responseSchema.properties[*].enum`에 **빈 문자열 금지**(400 `cannot be empty`). "해당 없음"은 빈 값이 아니라 **필드 생략**(required 제외)으로.
- **`propertyOrdering`이 곧 생성 순서 = 추론 순서.** 근거 필드를 앞에: `expected_result` → `evidence` → `action`. action을 먼저 두면 근거를 대충 채운다.
- 스키마 없이 프롬프트로만 요구하면 선택적 필드를 그냥 비운다(항상 `null`). **필수 필드는 스키마 `required`로 강제.**
- **스키마가 거부되면 모든 호출이 실패해 앱이 통째로 멈춘다 → 무스키마 재시도 폴백 필수.**
- 무료 티어 rate limit: flash-lite ≈ 15 RPM / 1,000 RPD. 429 `prepayment credits are depleted`는 사용량 초과가 아니라 결제 경로 문제일 수 있다. RPD 리셋은 한국시간 17:00.

## API 계약 결정 (2026-07-24 리뷰 → **2026-07-27 C1~C8 반영 완료**)

`app/schemas.py`는 앱·서버·eval 셋이 동시에 무는 지점이라 굳기 전에 검토했다.
아래 **확정 8건은 코드에 반영됐고 `tests/test_contract.py`가 고정한다.** 계약을 바꾸려면
그 테스트부터 고쳐야 하므로, 무심코 깨지지 않는다. **보류**는 근거가 생길 때까지 넣지 않는다.

### 확정 (반영 완료)
| # | 결정 | 근거 |
|---|---|---|
| C1 | **`propertyOrdering`을 pydantic 필드 순서에서 코드로 생성**한다. 손으로 배열을 적지 않는다. | `propertyOrdering`은 표준 JSON Schema가 아닌 **Gemini 전용 키**다. 지금은 파일 주석만 있고 실제 연결이 없다(`GeminiLlmClient.reason`이 stub). 두 벌을 손으로 맞추면 반드시 어긋나고, 어긋나면 HANDOFF §3-7(근거를 나중에 대충 채움)이 재발한다. |
| C2 | **`GuideAction.action` → `action_type`으로 개명**한다(또는 `NextStepResponse.action` → `step`). | 지금은 `resp.action.action`. Kotlin·eval 픽스처 양쪽에서 읽기 나쁘다. 지금이 가장 싸다. |
| C3 | **`GuideAction`에 `direction` 추가**(scroll 전용, 없으면 필드 생략). | HANDOFF §12 로그가 `act=tap node=n12 dir=null`. `dir`은 실재 필드이며, 방향 없는 scroll은 앱이 실행할 수 없다. |
| C4 | **`UiElement`에 `scroll_axis`, `next_swipe_direction`, `prev_swipe_direction` 추가.** | HANDOFF §4가 검증 자산으로 명시("방향·끝 판정이 여기서 나온다"). 앱이 채울 수 있는 값인데 누락됐다. |
| C5 | **`current_page`/`total_pages`를 `UiElement`에서 `Screen`으로 올린다.** | 화면 단위 상태다. 요소마다 들고 있으면 HANDOFF §2 버린 길("페이지마다 노드가 따로 있어 아무거나 집으면 틀린 값")을 서버에서 재현하는 구조가 된다. dot 자체는 `role="page_dot"` 요소로 남긴다. |
| C6 | **`ActionType.WAIT` 제거.** | HANDOFF에 근거가 없다(관찰된 것: tap / scroll+dir / open_quick_settings / done, 텍스트 입력으로 type). 정체 처리는 앱의 시간 기반 폴링 담당이라 LLM 액션일 이유가 없다. |
| C7 | **요청에 `client_version` 추가.** 스키마 버전 필드는 두지 않는다. | 파괴적 변경은 `/v1/` 경로로 충분하다. 진짜 문제는 비대칭 — 서버는 즉시 바뀌고 앱은 기기에 설치된 채 낡는다. 서버가 낡은 앱을 알아채야 한다. |
| C8 | **`ApiError`에 `retry_after_seconds`(선택)와 `request_id` 추가. `message`는 디버그용이며 사용자에게 그대로 노출하지 않는다.** | QUOTA에서 "잠시 후 다시" vs "오늘 한도 소진"(RPD 리셋 한국시간 17:00, §10)을 구분하려면 필요하다. 사용자 문구는 앱이 `kind`로 만든다. `request_id`는 도그푸딩 로그 대조용. |
| C9 | **`ActionType`에 `open_app_drawer` 추가** (2026-07-28). 아래 "보류"에 있던 전역 제스처 중 **근거가 나온 하나만** 올린다. | 실기기 관통에서 막혔다: `com.kakao.talk`이 설치돼 있는데 홈에 아이콘이 없어 33초 동안 같은 스크롤 안내가 8회 반복됐다. 홈 트리(노드 28개) 실측에서 앱서랍으로 가는 요소는 **없다** — 위로 쓸어올리는 제스처라 어떤 노드로도 표현되지 않는다. 즉 모델의 선택지에 정답이 존재하지 않았다. |
| C10 | **`Screen`에 `has_scrollable_element`(선택) 추가.** True/False/None 세 상태를 구분한다. | 요소를 훑어 유추하면 "스크롤 요소가 없다"와 "앱이 아직 안 채웠다"가 섞인다. 삼성 런처 홈은 `scrollable` 노드가 0개인데 그 침묵을 모델이 "넘길 수 있다"로 읽었다(위와 같은 실측). **없다는 것도 상태다**(루트 `CLAUDE.md`: 지식이 아니라 상태를 준다). 이름은 "화면이 스크롤된다"가 아니라 관찰한 사실 그대로다 — 트리에 스크롤 노드가 없어도 실제로는 넘어가는 화면이 있다(런처 페이징). |

**C1 운용 규칙:** `GuideAction`의 **필드 선언 순서가 곧 `propertyOrdering`**이다
(`gemini_schema.build_response_schema()`가 뽑고 `llm.GUIDE_ACTION_SCHEMA`가 들고 있다).
필드를 재배치하면 프롬프트 동작이 바뀐다. 순서를 바꿀 땐 이유를 PR에 적을 것.

### 보류
- **전역 제스처 액션 중 홈·뒤로** — 앱서랍은 실기기 근거가 나와 C9로 확정했다. **홈·뒤로는 아직 근거가 없어 보류 그대로다.** HANDOFF §3-14가 "홈 누르고 위로 쓸어올리세요"를 나쁜 예로 들어 홈 조작 안내가 존재함은 알 수 있으나, 그것만으로는 액션 타입이 필요한지 알 수 없다. **미리 넣으면 모델이 쓸 데 없이 고른다** — 이 사유는 여전히 유효하니 하나씩만 올린다.
- **`UiElement.selected`** — 페이지 dot의 `selected=true`는 앱의 직렬화 단계에서 `current_page` 계산에 쓰이고 끝난다(§3-3). 서버가 raw `selected`를 볼 이유가 아직 없다.
- **`bounds` 형태를 객체로** — 지금 `list[int]` 4원소는 uiautomator 관례와 맞고 앱/eval 양쪽에서 자연스럽다. 바꿀 이유 없음.

## 테스트
- API 계약(pytest): 요청/응답 스키마, 2차 마스킹, 에러 분류, 스키마 400 폴백.
- 추론 품질 회귀는 `../eval/` 하니스가 이 서버 코드를 직접 import해 돌린다 → 서버 함수는 **import 가능하게** 구성(HTTP 없이도 호출되게 순수 함수로 분리).
