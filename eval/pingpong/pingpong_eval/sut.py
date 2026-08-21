"""핑퐁 어댑터 — eval이 남의 코드(핑퐁)와 닿는 유일한 자리.

핑퐁의 형식이 바뀌어도 이 파일만 고친다. 사용자 LLM·평가자 LLM은 형식을 모른다.

지금 계약(davi ``POST /v1/intent/slot-fill``)은 텍스트 질문 하나라 ``options``가 비어서
돌아온다. 선택지 형식으로 바뀌면 응답에 선택지 배열이 실릴 뿐이고, 아래 필드 이름만 맞추면 된다.
"""

from __future__ import annotations

import os
import time
from typing import Any, Protocol

import httpx

from .models import PingpongStatus, PingpongTurn

# 선택지 형식이 어떤 이름으로 오든 받아 주려고 후보를 넓게 둔다. 확정되면 하나로 줄인다.
_OPTION_KEYS = ("options", "choices", "선택지")
_OTHER_KEYS = ("has_other", "allow_other", "기타")


class PingpongError(RuntimeError):
    pass


class PingpongAdapter(Protocol):
    def send(self, utterance: str, history: list[dict[str, str]]) -> PingpongTurn: ...


class SlotFillHttpAdapter:
    """davi 서버를 로컬에서 띄워 놓고 HTTP로 부른다. davi 코드는 건드리지 않는다."""

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
        # production 프로필이면 이 헤더는 무시된다.
        token = (dev_token or os.getenv("PINGPONG_DEV_TOKEN", "")).strip()
        self._headers = {"X-Davi-Dev-Token": token} if token else {}
        self._client = client or httpx.Client(timeout=timeout)

    def preflight(self) -> None:
        """서버가 떠 있고 스텁이 아닌지 본다. 스텁이면 채점이 무의미하다."""

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

    def send(self, utterance: str, history: list[dict[str, str]]) -> PingpongTurn:
        payload = {
            "utterance": utterance,
            "session_id": self._session_id,
            "conversation_history": history,
        }
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

            # 공유 서버의 요청 상한(#304, 창당 30회)에 우리 실행이 걸린다. 30개 시나리오면
            # 요청이 100회를 넘으므로 기다렸다 다시 보내는 것이 정상 경로다 — 실패가 아니다.
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

    options = next(
        (list(body[key]) for key in _OPTION_KEYS if isinstance(body.get(key), list)), []
    )
    has_other = next(
        (bool(body[key]) for key in _OTHER_KEYS if key in body),
        # 선택지가 없으면 자유 발화뿐이므로 '기타'는 언제나 열려 있는 셈이다.
        not options,
    )
    return PingpongTurn(
        status=status,
        question=str(body.get("message", "")),
        options=[str(option) for option in options],
        has_other=has_other,
        goal=body.get("goal"),
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
