# Pingpong 협업 계약

사용자 음성 입력 → 슬롯 채우기 → 최종 확인의 대화형 기능.
pfront(앱)과 pback(서버)이 함께 만드는 기능이므로, 이 문서에서 협업 방식을 정한다.

---

## 1. API 계약 (pfront ↔ pback)

### 엔드포인트
```
POST /v1/intent/slot-fill
```

### 요청 (pfront → pback)

```json
{
  "utterance": "카카오톡으로 지수님께 사진 보내기",
  "session_id": "session-abc123",
  "conversation_history": [
    {"role": "assistant", "content": "누구에게 보낼까요?"},
    {"role": "user", "content": "지수"}
  ]
}
```

**필드:**
- `utterance` (필수): 사용자 음성 (1차 마스킹된 상태)
- `session_id` (필수): 세션 ID (변경하지 말 것)
- `conversation_history` (선택): 최근 5턴까지

### 응답 (pback → pfront) — 성공

```json
{
  "reasoning": "카카오톡(app), 사진(content), 보내기(method), 지수님(target_person) 모두 채워짐",
  "action_type": "person_directed",
  "slots": {
    "app": "카카오톡",
    "content": "사진",
    "method": "보내기",
    "target_person": "지수"
  },
  "missing_slots": [],
  "confirmation_question": "카카오톡에서 지수님께 사진 보내드리는 게 맞으시죠?"
}
```

### 응답 — 에러

```json
{
  "kind": "QUOTA",
  "message": "API 한도 초과",
  "retry_after_seconds": 60,
  "request_id": "req-xyz789"
}
```

**에러 종류 → pfront의 사용자 메시지:**

| kind | pfront이 보여줄 메시지 |
|------|----------------------|
| `QUOTA` | "지금 다비가 조금 바빠요. 잠시 후 다시 말씀해주세요" |
| `NETWORK` | "인터넷 연결을 확인해주세요" |
| `AUTH` | "잠시 문제가 생겼어요. 다시 시도해주세요" |
| `SERVER` | "잠시 문제가 생겼어요. 다시 시도해주세요" |
| `PARSE` | "잠시 문제가 생겼어요. 다시 시도해주세요" |
| `BAD_REQUEST` | "잠시 문제가 생겼어요. 다시 시도해주세요" |

---

## 2. 동작 흐름

**음성 입출력은 둘 다 Android 기본 API를 쓴다** — 입력은 `SpeechRecognizer`,
출력은 `TextToSpeech`(외부 TTS 서비스를 쓰지 않는다). 읽어 주는 문장은 항상
pback이 돌려준 `confirmation_question` 그대로이며, 앱이 다시 쓰지 않는다.

### 화면 1: 대기
- pfront: 마이크 버튼만 표시

### 화면 2: 리스닝
- pfront: Google 음성 인식 시작, 텍스트 실시간 표시
- pback: (대기)

### 화면 3: 응답 처리

**missing_slots가 비어있을 때** (모든 정보 확인됨):
```
pback 응답:
  "missing_slots": [],
  "confirmation_question": "카카오톡에서 지수님께 사진 보내드리는 게 맞으시죠?"

pfront이 할 것:
  1. 말풍선으로 confirmation_question 표시
  2. TextToSpeech로 음성 읽기
  3. "네" / "아니요" 버튼 표시 + 음성 대기 (자동 인식 활성화)
  4. "네" 선택 → slots dict를 goal로 변환해 next_step() 파이프라인 시작
  5. "아니요" 선택 → 화면 2로 돌아가 처음부터 다시 시작 (새 session_id 발급)
```

**missing_slots가 있을 때** (정보 부족):
```
pback 응답:
  "missing_slots": ["target_person"],
  "confirmation_question": "누구에게 보낼까요?"

pfront이 할 것:
  1. 말풍선으로 confirmation_question 표시
  2. TextToSpeech로 음성 읽기
  3. 자동 음성 인식 재시작
  4. 사용자 답변을 받으면 conversation_history에 추가
  5. 다시 /v1/intent/slot-fill 호출
```

---

## 3. Session 관리

- **생성**: pfront이 첫 발화 때 UUID로 생성
- **유지**: 같은 목표 대화 동안 유지
- **갱신**: "아니요" 선택 후 새로 발급
- **전송**: 모든 요청에 포함

---

## 4. 이슈/PR 규칙

### 라벨 — pfront/pback이 구분 가능하도록

기존 규칙을 따르되, pingpong 관련 이슈는 **반드시** 다음 중 하나를 붙인다:

| 라벨 | 의미 |
|------|------|
| `pingpong-pfront` | 앱(화면, 음성 인식, TTS, UI 흐름) |
| `pingpong-pback` | 서버(SlotFillState 판단, LLM 호출) |
| `pingpong` | 둘 다 영향 (API 계약 변경 등) |

**예시:**
- `앱: 음성 인식 재시작 로직` → 라벨: `app`, `pingpong-pfront`
- `서버: LLM 토큰 제한` → 라벨: `server`, `pingpong-pback`
- `API 요청 스키마 변경` → 라벨: `pingpong` (둘 다 영향)

### 커밋 메시지

기존 규칙 준용:

```
pfront: 음성 인식 후 대기 상태 개선

[설명 2-3줄]

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>
```

또는:

```
pback: missing_slots 판정 로직 개선

[설명 2-3줄]

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>
```

### PR 머지 흐름

```
feature/pfront-... → pingpong PR → merge
feature/pback-...  → pingpong PR → merge
                   ↓
              pingpong → main (최종 배포)
```

**브랜치 네이밍:**
- 앱: `feature/pfront-음성인식`, `feature/pfront-TTS처리` 등
- 서버: `feature/pback-슬롯판정`, `feature/pback-LLM토큰` 등

**PR 규칙:**
- **대상**: 모두 `pingpong` 브랜치로
- **최소 1인 리뷰** (기존 규칙 유지)
- **PR 본문 체크리스트**:
  ```markdown
  - [ ] API 계약 준수
  - [ ] 에러 처리 명확
  ```
- **상대팀 알림** (강제 아님): PR 코멘트에 `@수현언니` 또는 `@개발자명`

---

## 5. 테스트 방식

### 단위 테스트 (각자)
- pfront: UI 화면 전환, 음성 입력 처리
- pback: resolve_intent() 함수, LLM 호출

### 통합 테스트 (함께)
- MockServer로 상대방을 시뮬레이션하고 테스트
- 또는 동시에 띄워서 수동 테스트

### 실기기 테스트 (SM-S921N)
- 음성 → 슬롯 판단 → 최종 확인까지 full flow
- 3-5번 반복해 안정성 확인

---

## 6. 머지 순서 — pback 먼저

```
1. feature/pback-*  → pingpong merge   (엔드포인트가 먼저 존재하게)
2. feature/pfront-* → pingpong merge   (그 엔드포인트를 호출)
3. pingpong → main                     (제품 반영)
```

**pback을 앞에 두는 이유**는 프론트가 붙을 대상이 먼저 있어야 하기 때문이다.
다만 pfront은 pback 머지를 기다리지 않는다 — `resolveIntent()` 한 함수 뒤에
mock을 두고 화면 흐름부터 완성하고, 실제 호출은 그 함수 내부만 바꾼다.
계약(§1)이 지켜지는 한 두 작업은 병렬로 간다.

---

## 7. 기존 문서와의 관계

**이 문서는:**
- ✅ pingpong 협업의 **계약**만 담는다
- ✅ 기존 CLAUDE.md 규칙을 따른다 (이슈, PR, 커밋 형식)
- ❌ 일반적인 규칙은 반복하지 않는다 → 기존 문서 참고

**참고:**
- 전체 시스템 설계 → `../SPEC.md`
- 앱 규칙 → `../app-android/CLAUDE.md`
- 서버 규칙 → `../server/CLAUDE.md`
- 팀 규칙 → `../CLAUDE.md`

---

## 8. 애매한 부분 — 의견 주세요

- [ ] **상대 팀원 리뷰**: 필수? 아니면 선택? (§4는 현재 "강제 아님")
- [ ] **실기기 테스트 담당**: 둘 다? 한 명?

확정되어 이 목록에서 내린 것 — 본문이 유일한 출처다.
Session 초기화(§3) · 대화 이력 범위(§1) · 머지 순서(§6).

---

**질문/건의는 이슈로 올려주세요!** 📝
