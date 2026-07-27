"""FastAPI 앱. 라우터는 얇게: reasoning.next_step을 호출하고 에러를 분류만 한다."""

from __future__ import annotations

import uuid

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from .config import settings
from .llm import LlmError, get_llm_client
from .reasoning import next_step
from .schemas import ApiError, ErrorKind, NextStepRequest, NextStepResponse

app = FastAPI(title="davi reasoning orchestrator", version="0.1.0")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": settings.davi_model, "stub_llm": settings.davi_use_stub_llm}


@app.post("/v1/next-step", response_model=NextStepResponse)
def post_next_step(req: NextStepRequest) -> NextStepResponse | JSONResponse:
    try:
        return next_step(req, get_llm_client())
    except LlmError as e:
        # 앱이 사용자에게 다르게 안내하도록 kind를 그대로 전달(HANDOFF §5, LlmException).
        # message는 디버그용이며 시니어에게 그대로 보여주지 않는다(C8).
        status = 429 if e.kind is ErrorKind.QUOTA else 502
        error = ApiError(
            kind=e.kind,
            message=e.message,
            retry_after_seconds=e.retry_after_seconds,
            request_id=uuid.uuid4().hex,
        )
        headers = {}
        if e.retry_after_seconds is not None:
            headers["Retry-After"] = str(e.retry_after_seconds)
        return JSONResponse(
            status_code=status,
            content=error.model_dump(),
            headers=headers or None,
        )
