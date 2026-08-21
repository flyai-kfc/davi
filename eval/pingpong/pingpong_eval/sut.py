"""핑퐁 어댑터 — eval이 남의 코드(핑퐁)와 닿는 유일한 자리.

핑퐁의 형식이 바뀌어도 이 파일만 고친다. 사용자 LLM·평가자 LLM은 형식을 모른다.

#326이 확정한 흐름은 **요청 두 번**이다.

1. 첫 요청 → ``clarification.questions``에 질문 1~2개와 질문별 보기(최대 3개)
2. 앱이 질문별 답을 **로컬에서** 모은다 (추가 호출 없음)
3. 두 번째 요청 → 같은 발화 + ``clarification_answers`` → ``ready`` 또는 ``unable_to_finalize``

#326 이전 형식(텍스트 질문 하나를 여러 왕복)은 ``legacy`` 질문 하나로 표현해 계속 잴 수 있다 —
그 형식으로 뜬 기준선과 새 형식을 같은 자료형으로 비교하기 위해서다.
"""

from __future__ import annotations

import os
import time
from typing import Any, Protocol

import httpx

from .models import PingpongStatus, PingpongTurn, Question, UserAnswer

_LEGACY_QUESTION_ID = "legacy-message"


class PingpongError(RuntimeError):
    pass


class PingpongAdapter(Protocol):
    def send(
        self,
        utterance: str,
        history: list[dict[str, str]],
        answers: list[UserAnswer],
    ) -> PingpongTurn: ...


class SlotFillHttpAdapter:
    """davi 서버를 띄워 놓고 HTTP로 부른다. davi 코드는 import하지 않는다."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8000",
        *,
        session_id: str,
        timeout: float = 60.0,
        dev_token: str | None = None,
        max_quota_retries: int = 5,
        client: httpx.Client | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._session_id = session_id
        self._max_quota_retries = max_quota_retries
        # 배포된 서버가 development 프로필이면 팀 토큰이 필요하다(`X-Davi-Dev-Token`).
        token = (dev_token or os.getenv("PINGPONG_DEV_TOKEN", "")).strip()
        self._headers = {"X-Davi-Dev-Token": token} if token else {}
        self._client = client or httpx.Client(timeout=timeout)

    def preflight(self) -> None:
        """서버가 떠 있고 **스텁이 아닌지** 본다.

        스텁 해석기는 무조건 ``ready``에 발화를 그대로 goal로 돌려준다. 그 상태로도 보고서는
        멀쩡히 만들어지므로, 막지 않으면 의미 없는 수치가 실제 기준선처럼 남는다.
        """

        try:
            response = self._client.get(
                f"{self._base_url}/health", headers=self._headers
            )
        except httpx.RequestError as error:
            raise PingpongError(
                f"davi 서버에 닿지 못했다({self._base_url}). "
                "DAVI_USE_STUB_LLM=false 로 서버를 먼저 띄운다."
            ) from error
        if response.is_error:
            raise PingpongError(f"/health 가 {response.status_code} 를 냈다")

        try:
            body = response.json()
        except ValueError as error:
            raise PingpongError("/health 응답이 JSON이 아니다") from error

        # production은 status만 알린다(#279). 그 프로필은 설정 자체가 스텁을 금지하므로 통과시킨다.
        if body.get("stub_llm") is True:
            raise PingpongError(
                "서버가 스텁 해석기로 떠 있다(stub_llm=true). 스텁은 언제나 ready에 발화를 "
                "그대로 goal로 돌려주므로 채점이 의미가 없다. DAVI_USE_STUB_LLM=false 로 다시 띄운다."
            )

    def send(
        self,
        utterance: str,
        history: list[dict[str, str]],
        answers: list[UserAnswer] | None = None,
    ) -> PingpongTurn:
        payload: dict[str, Any] = {
            "utterance": utterance,
            "session_id": self._session_id,
            "conversation_history": history,
        }
        if answers:
            payload["clarification_answers"] = [answer.to_wire() for answer in answers]

        started = time.monotonic()
        for attempt in range(self._max_quota_retries + 1):
            try:
                response = self._client.post(
                    f"{self._base_url}/v1/intent/slot-fill",
                    json=payload,
                    headers=self._headers,
                )
            except httpx.RequestError as error:
                raise PingpongError(f"핑퐁 요청 실패: {error}") from error

            # 공유 서버의 요청 상한(#304)에 걸리는 것은 정상 경로다 — 기다렸다 다시 보낸다.
            if response.status_code == 429 and attempt < self._max_quota_retries:
                time.sleep(_retry_after(response))
                continue
            break

        latency_ms = int((time.monotonic() - started) * 1000)

        if response.status_code == 401:
            raise PingpongError(
                "서버가 401을 냈다. 배포가 development 프로필이면 PINGPONG_DEV_TOKEN "
                "(=DAVI_DEV_ACCESS_TOKEN 또는 DAVI_TEST_ACCESS_TOKEN)을 넣는다."
            )
        if response.is_error:
            raise PingpongError(
                f"핑퐁 HTTP {response.status_code}: {response.text[:200]}"
            )

        return _parse(response.json(), latency_ms)

    def close(self) -> None:
        self._client.close()


def _parse(body: dict[str, Any], latency_ms: int) -> PingpongTurn:
    try:
        status = PingpongStatus(body["status"])
    except (KeyError, ValueError) as error:
        raise PingpongError(
            f"핑퐁 응답에 status가 없거나 모르는 값이다: {body!r}"
        ) from error

    message = str(body.get("message", ""))
    plan = body.get("clarification")
    questions: list[Question] = []
    legacy = False

    if isinstance(plan, dict) and isinstance(plan.get("questions"), list):
        for raw in plan["questions"]:
            if not isinstance(raw, dict):
                raise PingpongError(f"질문 항목이 객체가 아니다: {raw!r}")
            try:
                questions.append(
                    Question(
                        id=str(raw["id"]),
                        prompt=str(raw["prompt"]),
                        options=[str(option) for option in raw.get("options", [])],
                        allow_freeform=bool(raw.get("allow_freeform", True)),
                    )
                )
            except KeyError as error:
                raise PingpongError(f"질문에 {error} 가 없다") from error
        if not questions:
            raise PingpongError("clarification.questions가 비어 있다")
    elif status is PingpongStatus.NEEDS_CLARIFICATION:
        # #326 이전 형식 — 질문은 message 한 문장뿐이고 보기가 없다.
        legacy = True
        questions = [Question(id=_LEGACY_QUESTION_ID, prompt=message, options=[])]

    return PingpongTurn(
        status=status,
        message=message,
        questions=questions,
        goal=body.get("goal"),
        legacy=legacy,
        latency_ms=latency_ms,
    )


def _retry_after(response: httpx.Response) -> float:
    """Retry-After 헤더가 우선, 없으면 본문의 retry_after_seconds, 그것도 없으면 1초."""

    header = response.headers.get("Retry-After")
    if header and header.isdigit():
        return float(header)
    try:
        return float(response.json().get("retry_after_seconds") or 1)
    except (ValueError, AttributeError):
        return 1.0
