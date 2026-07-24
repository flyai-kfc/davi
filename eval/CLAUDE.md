# CLAUDE.md — eval (평가 하니스)

Python. 기기 없이 [화면 픽스처 → 다음 안내]를 회귀 검증한다. PoC2 하니스(`reference/poc2-llm-reasoning/`) 이식.
공통 규칙은 루트 `../CLAUDE.md`. 전략은 `../SPEC.md` §8.

## 이 폴더의 책임
- **회귀 테스트** — `../server/`의 추론 코드를 직접 import해 픽스처로 돌린다. 서버가 바뀌면 여기서 잡힌다.
- **LLM-judge 채점(§1-3 도입).** 골든패스 exact-match는 동등/더 나은 대안 경로를 오답 처리한다(PoC2 FAIL 4건이 전부 이 착시). judge로 대체.
- **픽스처 관리** — 도그푸딩 기기 로그의 실패 화면을 **개인정보 익명화 후** 추가(§12, 실제로 연락처 이름을 치환해 사용).

## 실행
- `py -3.12` 고정(litellm 3.11+). `py -3.12 main.py --models a,b --min-interval 4` → `runs/`에 JSON → 시각화.
- rate limit 대응: `--min-interval`(15 RPM이면 4초), `--retries`(백오프).
- API 키는 환경변수. 커밋 금지.

## 마음가짐
- 현재 수치(sonnet-5 97% / flash-lite 91%)는 **깨끗한 픽스처 기준 낙관적 상한**이다. 실트리는 토큰이 2배고 노이즈가 많다. 보고할 때 이 단서를 계속 붙인다.
- 새 픽스처를 넣을 때 반드시 익명화. 실제 개인정보를 저장소에 올리지 않는다.
