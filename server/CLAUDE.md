# CLAUDE.md — server (추론 오케스트레이터)

Python 3.12 / FastAPI. 앱이 보낸 [마스킹된 화면 + 목표]로 **다음 안내 하나**를 정하고,
음성 목표가 불완전하면 LLM으로 후속 질문 하나를 생성한다.
공통 규칙·도메인 용어는 루트 `../CLAUDE.md`. 방향은 `../SPEC.md`.

## 이 폴더의 책임
- **2차 마스킹 그물** — 앱 1차 마스킹이 놓친 패턴 방어. 원격 업데이트 가능(앱 재배포 불필요).
  적용 여부는 `masking.MaskingPolicy`가 값으로 들고 `reasoning.next_step()`이 주입받는다.
  우회(`DAVI_DISABLE_PRIVACY_MASKING=true`)는 **development + loopback + 개발 토큰 없음**에서만
  기동하는 dev-only 비교 실험이며, 켜지면 raw 봉투 저장이 꺼진다(#203, `../SPEC.md` §3).
- **프롬프트 구성 + 가드레일** — 앱별/버전별 지식과 일반 정책을 여기 둔다. 가드는 **단일 결정 지점**으로 통합(가드끼리 덮어써 불안정했던 §5-4 전례 회피).
- **모델 호출** — `gemini-3.5-flash-lite`. `DAVI_MODEL` 환경변수로 교체 가능(`Settings.davi_model`).
- **완료 검증** — `expected_result`/`evidence`를 써내게 하고 비교해 조기 종료 방지.
- **음성 목표 정리** — 고정 슬롯·문자열 조건 없이 LLM 구조화 출력으로 질문 필요/goal 준비를 판정.
- **목표 앱 후보** — 전체 설치 목록 없이 일반 후보 최대 5개를 만들고 기기의 ID/status 피드백을
  무상태로 판정한다. 기기에서만 발견한 앱 정보는 받지 않는다(#102·#128).
- **에러 분류** — QUOTA/NETWORK/AUTH/SERVER/PARSE/BAD_REQUEST. 앱이 사용자에게 다르게 안내하도록.
- **카카오 로그인 검증·세션 발급**(`kakao_auth.py`, #165) — 앱이 받아 온 카카오 access token을
  검증(발급처 `app_id` 대조)하고 카카오 회원번호를 담은 **무상태 세션 JWT**를 발급한다.
  사용자 DB 없음. 인증 전 공개 endpoint라 dev 게이트와 별개로 **모든 프로필에서 도는**
  로그인 전용 레이트리밋이 붙는다 — 출처(직접 연결 peer IP)별 제한이 정상 사용자를,
  전역 상한이 카카오 API 소모 총량을 지킨다. 세션 토큰이 담긴 성공 응답은
  `Cache-Control: no-store`다. 카카오 토큰·세션 토큰·서명 비밀은 로그 금지.

## 추론 아키텍처 — 실행 → 명시적 검증 2단
1. **실행** — 현재 화면에서 다음 한 걸음.
2. **명시적 검증** — 완료 판정을 별도 단계로(`_verify_done`, #62).

**계획(sub-task) 단계는 무기한 연기됐다**(2026-08-18, `../SPEC.md` §3 비목표). PoC의 단일 스텝
반응형을 넘어선 것은 **검증 단계와 가드레일 단일 결정 지점**이지 계획이 아니다. `reasoning.py`에
`_plan` 스텁이 남아 있으나 결과를 쓰지 않는다 — 걷어내는 것은 별건이다.

## 실행
- **`py -3.12` 고정.** 원래 근거였던 litellm은 빠졌고, 지금은 코드가 `StrEnum`·`typing.Self`
  (3.11+)를 쓴다(`../SPEC.md` §5 표와 같은 근거).
- 개인 USB 검증은 loopback+`adb reverse`, 팀 공용 검증은 같은 사설 Wi-Fi의 전용 맥북 또는
  #94 Azure Container Apps development endpoint로 접속한다(`../SPEC.md` §7). Azure는 HTTPS와
  개발 토큰을 요구하는 dev-only 예외이며 production 공개 서비스가 아니다.
- API 키는 **환경변수**. 커밋 금지. `.env`는 gitignore됨.
- `DAVI_ENV` 미설정은 로컬 호환을 위해 `development`. 제품 배포는 `production`을 명시하고
  실제 LLM + non-empty 키를 사용하며 `DAVI_DEV_ACCESS_TOKEN`을 두지 않는다. #94 Azure 공유 서버는
  제품 배포가 아닌 dev-only 경로라 `development`와 개발 토큰을 유지한다. 잘못된 조합은
  `config.Settings`가 import 시 거부한다.
- development를 loopback 밖에 바인딩할 때는 32자 이상 `DAVI_DEV_ACCESS_TOKEN`이 필수다.
  공용 스크립트는 worker 하나와 팀 전체 10회/60초 제한으로 Gemini 쿼터를 보호한다.
- 요청 로그에는 비식별 운영 메타데이터만 둔다. `goal`, 화면 label, 최근 안내, 음성 발화·대화 이력·
  대화 `session_id`, 모델의 질문/goal, 요청 본문, API 키·access token은 어떤 프로필에서도 기록하지 않는다.
- 정상 요청은 Logical Request ID/attempt와 서버 request ID로 앱→Gemini 단계를 연결한다(#81).
  정상 `/health`와 기본 access log는 남기지 않으며 IP/port·User-Agent도 보존하지 않는다.
  로그 형식은 UTC timestamp가 있는 JSON Lines다. 공용 맥 스크립트는 7일, Azure Log Analytics는
  30일 보존한다. Azure에서는 도그푸딩 device allowlist를 비워 raw 저장을 끈다.
- **픽스처 보존(#41)은 운영 로그와 별개 채널이다.** 정책 원본은 `../SPEC.md` §3
  "도그푸딩 raw 보존 정책"(대상·내용·위치·기간·접근 범위). 여기서는 경계만 재확인한다 —
  위 운영 로그의 비식별 불변식은 이 채널과 무관하게 유지되며, 비화이트리스트
  `device_id`도 로그에 남기지 않는다.

## Gemini 구조화 출력 함정 (`HANDOFF.md` §10 — 실측)
- `responseSchema.properties[*].enum`에 **빈 문자열 금지**(400 `cannot be empty`). "해당 없음"은 빈 값이 아니라 **필드 생략**(required 제외)으로.
- **`propertyOrdering`이 곧 생성 순서 = 추론 순서.** 근거 필드를 앞에: `expected_result` → `evidence` → `action`. action을 먼저 두면 근거를 대충 채운다.
- 스키마 없이 프롬프트로만 요구하면 선택적 필드를 그냥 비운다(항상 `null`). **필수 필드는 스키마 `required`로 강제.**
- **스키마가 거부되면 모든 호출이 실패해 앱이 통째로 멈춘다 → 무스키마 재시도 폴백 필수.**
- `GuideAction` 한 객체의 정상 출력에 충분한 여유를 두고 `maxOutputTokens=1024`로 장문 응답의
  지연·비용 상한을 고정한다. schema 400 무스키마 폴백과 복구 재시도에도 같은 상한을 쓴다.
  후보가 `finishReason=MAX_TOKENS`로 끝나면 잘린 JSON을 일반 `invalid_json`으로 뭉개지 않고
  `max_output_tokens` 진단의 PARSE 실패로 분류한다. 로그에는 후보 텍스트를 남기지 않는다.
- 후보 텍스트 추출·`MAX_TOKENS` 판별·JSON fence 제거는 `gemini_response.py`의 순수 함수로
  eval과 공유한다. HTTP 재시도, 로그, 서비스·평가 오류 분류는 각 호출 경계에 남긴다.
- **enum 클래스 docstring은 스키마에 실리지 않는다.** `gemini_schema`가 responseSchema에 싣는 것은
  **필드의 `description`**뿐이라, `SwipeDirection` docstring에만 있던 방향 기준이 모델에
  도달하지 않았다(#109 원인). 값의 뜻을 모델에게 말하려면 **필드 description이나 프롬프트**에
  둔다 — 그 둘은 서로 같은 뜻이어야 한다(무스키마 폴백에서 한 프롬프트에 함께 들어간다).
- **모델이 지키지 못하는 기준은 프롬프트로도 안 고쳐진다.** `direction`을 손가락 기준으로 두고
  문구 규칙까지 넣었더니(#109), 모델이 자연스러운 문장("아래로 스크롤")에 **값을 끌어다 맞춰**
  원래 맞던 값이 틀리게 바뀌었다(2026-08-17 실기기 A/B). 규칙을 더 얹기 전에 **모델이 이미
  맞히는 프레임이 무엇인지** 보고, 가능하면 계약을 그쪽으로 옮긴다(C17).
- `scroll`인데 `direction`이 빠지면 화면 메타로 앞/뒤 방향을 추측하지 않는다. 같은 schema 모드에서
  방향을 명시하도록 **한 번만 재요청**하고, 다시 빠지면 PARSE로 실패한다. 로그에는 응답 원문 대신
  `scroll_direction_missing` 진단 코드만 남긴다.
- 음성 목표 판단은 공개 wire 모델과 Gemini 내부 모델을 분리하고, 내부 `decision_basis`를
  `status`보다 먼저 생성한다. `needs_clarification`의 불필요한 goal만 제거하며, ready goal 누락·
  공백과 텍스트 상한 위반은 한 번 교정한다. schema 400 폴백을 포함해 요청당 총 2회만 호출하고,
  두 번째 위반은 PARSE로 닫는다. 로그에는 고정 reason code만 남긴다.
- 무료 티어 rate limit: flash-lite ≈ 15 RPM / 1,000 RPD. 429 `prepayment credits are depleted`는 사용량 초과가 아니라 결제 경로 문제일 수 있다. RPD 리셋은 한국시간 17:00.

## API 계약 결정 (2026-07-24 리뷰 → **2026-08-13 C1~C15 반영** → **2026-08-14 C16** → **2026-08-17 C17**(2026-08-18 재확정))

`app/schemas.py`는 앱·서버·eval 셋이 동시에 무는 지점이라 굳기 전에 검토했다.
아래 확정 사항은 코드에 반영됐고 `tests/test_contract.py`가 고정한다. 계약을 바꾸려면
그 테스트부터 고쳐야 하므로, 무심코 깨지지 않는다. **보류**는 근거가 생길 때까지 넣지 않는다.

**C16은 스키마를 바꾸지 않기로 한 결정이라 반영할 스키마 코드가 없다.** 예시 문장을 만드는
프롬프트 구현은 #140이 추적한다 — 루트 `CLAUDE.md`대로 문서(이 표와 `SPEC.md` §4)가 코드보다
먼저 들어왔다.

### 확정 (반영 완료)
| # | 결정 | 근거 |
|---|---|---|
| C1 | **`propertyOrdering`을 pydantic 필드 순서에서 코드로 생성**한다. 손으로 배열을 적지 않는다. | `propertyOrdering`은 표준 JSON Schema가 아닌 **Gemini 전용 키**다. 지금은 파일 주석만 있고 실제 연결이 없다(`GeminiLlmClient.reason`이 stub). 두 벌을 손으로 맞추면 반드시 어긋나고, 어긋나면 HANDOFF §3-7(근거를 나중에 대충 채움)이 재발한다. |
| C2 | **`GuideAction.action` → `action_type`으로 개명**한다(또는 `NextStepResponse.action` → `step`). | 지금은 `resp.action.action`. Kotlin·eval 픽스처 양쪽에서 읽기 나쁘다. 지금이 가장 싸다. |
| C3 | **`GuideAction`에 `direction` 추가**(scroll 전용, 없으면 필드 생략). **뜻은 C17로 재정의됐다 — 보러 가는 방향이다.** | HANDOFF §12 로그가 `act=tap node=n12 dir=null`. `dir`은 실재 필드이며, 방향 없는 scroll은 앱이 실행할 수 없다. |
| C4 | **`UiElement`에 `scroll_axis`, `next_swipe_direction`, `prev_swipe_direction` 추가.** **이 둘의 기준도 C17을 따른다** — `next`는 다음 내용을 보러 가는 방향이다(세로 목록이면 `down`). | HANDOFF §4가 검증 자산으로 명시("방향·끝 판정이 여기서 나온다"). 앱이 채울 수 있는 값인데 누락됐다. |
| C5 | **`current_page`/`total_pages`를 `UiElement`에서 `Screen`으로 올린다.** | 화면 단위 상태다. 요소마다 들고 있으면 HANDOFF §2 버린 길("페이지마다 노드가 따로 있어 아무거나 집으면 틀린 값")을 서버에서 재현하는 구조가 된다. dot 자체는 `role="page_dot"` 요소로 남긴다. |
| C6 | **`ActionType.WAIT` 제거.** | HANDOFF에 근거가 없다(관찰된 것: tap / scroll+dir / open_quick_settings / done, 텍스트 입력으로 type). 정체 처리는 앱의 시간 기반 폴링 담당이라 LLM 액션일 이유가 없다. |
| C7 | **요청에 `client_version` 추가.** 스키마 버전 필드는 두지 않는다. | 파괴적 변경은 `/v1/` 경로로 충분하다. 진짜 문제는 비대칭 — 서버는 즉시 바뀌고 앱은 기기에 설치된 채 낡는다. 서버가 낡은 앱을 알아채야 한다. |
| C8 | **`ApiError`에 `retry_after_seconds`(선택)와 `request_id` 추가. `message`는 디버그용이며 사용자에게 그대로 노출하지 않는다.** | QUOTA에서 "잠시 후 다시" vs "오늘 한도 소진"(RPD 리셋 한국시간 17:00, `HANDOFF.md` §10)을 구분하려면 필요하다. 사용자 문구는 앱이 `kind`로 만든다. `request_id`는 도그푸딩 로그 대조용. |
| C9 | **`ActionType`에 `open_app_drawer` 추가** (2026-07-28). 아래 "보류"에 있던 전역 제스처 중 **근거가 나온 하나만** 올린다. | 실기기 관통에서 막혔다: `com.kakao.talk`이 설치돼 있는데 홈에 아이콘이 없어 33초 동안 같은 스크롤 안내가 8회 반복됐다. 홈 트리(노드 28개) 실측에서 앱서랍으로 가는 요소는 **없다** — 위로 쓸어올리는 제스처라 어떤 노드로도 표현되지 않는다. 즉 모델의 선택지에 정답이 존재하지 않았다. |
| C10 | **`Screen`에 `has_scrollable_element`(선택) 추가.** True/False/None 세 상태를 구분한다. | 요소를 훑어 유추하면 "스크롤 요소가 없다"와 "앱이 아직 안 채웠다"가 섞인다. 삼성 런처 홈은 `scrollable` 노드가 0개인데 그 침묵을 모델이 "넘길 수 있다"로 읽었다(위와 같은 실측). **없다는 것도 상태다**(루트 `CLAUDE.md`: 지식이 아니라 상태를 준다). 이름은 "화면이 스크롤된다"가 아니라 관찰한 사실 그대로다 — 트리에 스크롤 노드가 없어도 실제로는 넘어가는 화면이 있다(런처 페이징). |
| C11 | **앱이 `recent_actions`를 세션 안에 최대 10개 보관하고, 서버가 돌려준 구조 기반 `screen_signature`와 함께 다음 요청에 보낸다. 서버는 무상태로 반복을 판정한다.** | `recent_instructions`는 문구가 매번 달랐던 8회 scroll을 정확히 식별할 수 없다. `device_id`는 기기와 세션을 혼동하고, 서버 캐시는 TTL·멀티워커·세션 누출 문제를 만든다. #44의 generation 격리와 함께 구조화 이력을 비우면 같은 목표 재시작도 분리된다. 서명은 label을 포함하지 않아 시계·배터리 변동과 원문 label 해시의 재식별 위험을 피한다. |
| C12 | **앱이 비식별 `X-Davi-Logical-Request-ID`와 `X-Davi-Attempt`를 HTTP header로 보내고 서버가 검증·echo·단계별 로그 연결에 사용한다. JSON 본문은 바꾸지 않는다.** | 앱이 10초에 timeout된 뒤 서버가 최대 20초 넘게 처리해 같은 화면 호출 5건이 겹쳤지만, 서버가 나중에 만든 `X-Request-ID`는 응답을 못 받은 앱과 대조할 수 없었다. ID는 무작위 UUID이며 goal·label·화면 서명·기기 ID에서 파생하지 않는다. #81은 관측만, dedupe·cache·timeout은 #82다. |
| C13 | **`/v1/intent/slot-fill`은 고정 슬롯 목록 없이 `needs_clarification`/`ready`, 사용자 `message`, 선택 `goal`을 반환한다**(되묻기 답변 예시도 필드가 아니라 `message` 문장에 싣는다 — C16). 요청 이력은 이전에 끝난 user/assistant 쌍이고 `utterance`가 현재 발화다. 내부 Gemini 모델은 짧은 `decision_basis`를 먼저 생성하며 wire·로그에는 내보내지 않는다. | #88의 사람 표식·답변 위치 규칙은 자연어 변형과 무관한 답변을 오분류했고, 슬롯 taxonomy가 새 목표마다 서버·앱 계약을 키운다. 질문 자체를 LLM이 생성하고 앱은 상태와 TTS만 맡는다. 무작위 `session_id`는 모델·로그·서버 상태에 쓰지 않는다. 결론보다 근거를 먼저 생성하는 C1 순서를 새 LLM 경계에도 유지한다. |
| C14 | **`UiElement.editable`·`focused`와 선택 `Screen.ime_visible`을 서로 다른 입력 상태로 보낸다.** | `editable+focused`는 사용자가 입력 대상을 선택했다는 주 신호이고, `ime_visible`은 소프트 키보드 창 관측이다(#112). 둘만으로 추가 입력이 필요하다고 단정하지 않는다. 입력 준비 상태에서는 목표에 맞는 결과·후속 UI가 이미 보이는지 LLM이 먼저 판단하고, 보이면 포커스와 IME가 유지돼도 그 조작을 우선한다(#120). 그런 UI가 없고 입력이 더 필요할 때 `true`이면 같은 칸의 재탭보다 `type`, `false`이면 키보드를 여는 탭을 먼저 안내한다. 미관측 `None`은 기존 앱의 입력 안내를 막지 않는다. 일반 입력 원문과 IME 키 트리는 보내지 않는다. 구조 검증된 `NumberPicker`의 짧은 숫자는 사용자 입력 원문이 아니라 직접 조정한 선택 상태이므로 예외로 보존한다(#117). 어느 상태로도 입력 완료·목표 완료를 추론하지 않는다. 낡은 앱의 필드 누락은 `false`·`None`, 빈 window 목록은 미관측 `None`으로 받는다. |
| C15 | **`/v1/intent/app-resolution`은 goal→일반 후보 plan, plan+기기 `candidate_id/status`→최종 상태의 2단 무상태 계약이다.** 후보는 최대 5개이고 앱이 server-origin plan을 echo하므로 서버 세션 캐시가 없다. | LLM은 앱 의미를 알지만 설치 상태를 모르고, Android는 launcher 앱을 알지만 의미를 일반화할 수 없다. 전체 설치 목록이나 기기 고유 label/package를 보내지 않고 두 판단을 결합한다(#102·#128). |
| C16 | **되묻기 답변 예시는 별도 필드가 아니라 `needs_clarification`의 `message` 문장에 싣는다 — 보기 목록 필드를 계약에 추가하지 않는다.** 질문 문장은 열린 채로 두고 예시는 뒤따르는 별도 문장("예를 들면 ~요")이다. 개수는 스키마가 아니라 프롬프트로 통제한다(많아야 둘, 줄 것이 없으면 붙이지 않는다 — 예시 없는 질문도 정상). 프롬프트는 **고유명 생성을 금지하고 행위 유형 예시만 허용**한다. 프롬프트 구현은 #140. | 되묻기가 열린 질문 하나뿐이라 막연한 답이 돌아오고 같은 질문이 다시 나갔다. 5왕복 상한까지 헛돌면 실패 화면으로 끝나 처음부터 다시다(#123). 필드로 실으면 화면에 선택지로 그려지는 길이 열리는데, **화면에 뜬 보기는 예시가 아니라 선택지로 읽혀** 원하는 답이 없어도 어르신이 그중에서 고르고, 개수가 자리를 만들면 줄 근거가 없는 자리에서도 모델이 채운 것이 **어르신이 "응 그거"로 받아버리는 자리**에 뜬다. 고유명이 긍정되면 없는 대상이 `goal`에 박혀 되돌릴 지점이 없고, 행위 유형은 틀려도 부정되고 끝난다 — 그래서 금지 대상은 추측 자체가 아니라 고유명 생성이다. 근거: `docs/voice-goal.md` §2 [합의] F(2026-08-14 팀 합의, 칩·별도 필드 초안을 물린 경위 포함). |

| C17 | **`SwipeDirection`을 "손가락이 움직이는 방향"에서 "화면이 이동하는 방향(= 보러 가는 쪽)"으로 재정의한다**(2026-08-17 결정 → 리뷰로 한 차례 물렸다가 **2026-08-18 재확정**, #191). 아래 내용을 보려면 `down`, 위로 되돌아가려면 `up`, 다음 페이지는 `right`, 이전 페이지는 `left`. `UiElement`의 `next`/`prev_swipe_direction`도 같은 기준이다(C4). **필드도 값 집합도 그대로지만 같은 값이 반대를 가리키므로 앱·서버가 함께 바뀌어야 한다.** `instruction`은 같은 말로 쓰고("아래쪽으로 스크롤해 주세요"), **손가락 방향으로의 변환은 앱이 그릴 때 한 번** 한다(`GestureMotion.fingerDirectionFor`). | 손가락 기준을 모델이 지키지 못했다. "아래 내용을 보여줘야 하니 `down`"이 모델에게 자연스럽고, 프롬프트로 기준을 못박은 뒤(#109)에도 같은 화면에서 재발했다. 오히려 문구 규칙이 값을 끌어당겨 **원래 맞던 값을 틀리게** 만든 A/B가 있다(2026-08-17 SM-S921N, 카카오톡 설정 `element_count=17` 동일 화면: 머지 전 `up`(맞음) → 머지 후 `down`(틀림)). 앱에서 조건부로 교정하는 안은 실기기에서 실패했고, 무조건 뒤집기는 서버가 맞는 화면을 망친다 — **판정이 필요 없는 유일한 길은 계약을 모델이 맞히는 프레임에 두는 것**이다. 변환 규칙은 예외가 없어 코드가 안전하게 맡는다. 덤으로 시니어에게 나가는 문장이 자연스러워진다("손가락을 대고 위로 쓸어올려 주세요" → "아래쪽으로 스크롤해 주세요"). **한 차례 물렸던 경위도 결정의 일부다** — "관측된 문제는 손 표시 하나이니 렌더 경계에서만 반전하고 계약은 두자"는 리뷰를 받아 되돌렸었다(2026-08-17). 그 안이 접힌 이유는 실기기 제품 경로에서 **`direction`과 실제 손가락 방향이 100% 반대로** 관측됐기 때문이다(2026-08-18 #159 승인). 어긋남이 예외 없이 전면적이면 그건 계약이 틀린 것이지 렌더 버그가 아니고, 계약을 그대로 둔 채 앱만 뒤집으면 **프롬프트는 손가락 기준을 지시하는데 앱은 그 반대를 그리는** 모순이 저장소에 남는다. 리뷰가 든 호환성 우려(기존 설치 APK와 서버가 한 값을 반대로 읽음)는 **앱·서버를 연달아 머지해 사고 구간을 짧게 가져가는 것**으로 다룬다. |

### C11 반복 가드 정책

- 화면 서명은 package/page/IME와 요소의 순서·node id·role·bounds·클릭/편집/포커스/스크롤 상태만 포함한
  SHA-256이다. label의 원문·해시·길이는 모두 제외한다.
- 같은 서명에서 `action_type`·`direction`·`target_node_id`가 같은 행동은 두 번까지 허용하고,
  세 번째 후보부터 fallback을 검토한다.
- 현재 안전한 fallback은 실기기 근거가 있는
  `com.sec.android.app.launcher + has_scrollable_element=false + scroll 반복`의
  `open_app_drawer` 전환뿐이다. 앱서랍도 앞의 두 조건이 같으므로 page 메타가 하나라도 있는
  런처 화면에는 적용하지 않는다.
- label을 제외한 서명은 구조·좌표가 같고 내용만 바뀐 화면을 구분하지 못한다. 현재의 좁은
  런처 fallback 밖에서 화면 동일성의 보편적 근거로 재사용하지 않는다. 서명 알고리즘 변경은
  진행 중 이력을 불일치시켜 카운트를 끊는 fail-safe 방향이다.
  막힘을 성공으로 오인시키므로 `done`은 사용하지 않는다.
- 앱이 사용자에게 실제로 표시한 행동만 기록한다. 세션 중지·목표 변경·같은 목표 재시작 때
  instruction/action 이력을 함께 비우며, 이전 generation의 늦은 응답은 기록·표시·`done`
  처리 전에 버린다.
- 서버에는 세션 상태가 없으므로 TTL 정리 대상도 없다. 앱의 두 이력은 각각 최대 10개다.

**C1 운용 규칙:** `GuideAction`의 **필드 선언 순서가 곧 `propertyOrdering`**이다
(`gemini_schema.build_response_schema()`가 뽑고 `llm.GUIDE_ACTION_SCHEMA`가 들고 있다).
필드를 재배치하면 프롬프트 동작이 바뀐다. 순서를 바꿀 땐 이유를 PR에 적을 것.

### 보류
- **tap/type/일반 앱 scroll 반복의 제품적 막힘 응답** — 현재 계약에는 "진행 불가"가 없고
  임의의 다른 행동은 안전하지 않다. `done`은 성공 완료 의미라 대체할 수 없다. 실기기 근거와
  사용자 경험 결정을 얻은 뒤 별도 action/error 계약으로 검토한다.
- **전역 제스처 액션 중 홈·뒤로** — 앱서랍은 실기기 근거가 나와 C9로 확정했다. **홈·뒤로는 아직 근거가 없어 보류 그대로다.** HANDOFF §3-14가 "홈 누르고 위로 쓸어올리세요"를 나쁜 예로 들어 홈 조작 안내가 존재함은 알 수 있으나, 그것만으로는 액션 타입이 필요한지 알 수 없다. **미리 넣으면 모델이 쓸 데 없이 고른다** — 이 사유는 여전히 유효하니 하나씩만 올린다.
- **`UiElement.selected`** — 페이지 dot의 `selected=true`는 앱의 직렬화 단계에서 `current_page` 계산에 쓰이고 끝난다(§3-3). 서버가 raw `selected`를 볼 이유가 아직 없다.
- **`bounds` 형태를 객체로** — 지금 `list[int]` 4원소는 uiautomator 관례와 맞고 앱/eval 양쪽에서 자연스럽다. 바꿀 이유 없음.

## 테스트
- API 계약(pytest): 요청/응답 스키마, 2차 마스킹, 음성 대화 턴 경계, 에러 분류, 스키마 400 폴백.
- 추론 품질 회귀는 `../eval/` 하니스가 이 서버 코드를 직접 import해 돌린다 → 서버 함수는 **import 가능하게** 구성(HTTP 없이도 호출되게 순수 함수로 분리).
