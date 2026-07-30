"""도그푸딩 기기의 요청·응답 봉투 보존(#41) — 운영 로그와 별개 채널.

수신 그대로(1차 마스킹 후)의 요청과 서버가 돌려준 응답을 JSON 한 파일로, 서버가 도는
머신에 영구 보존한다(자동 삭제 없음, 정리는 수동). 화이트리스트 판정과 best-effort
처리는 호출부(main)가 맡고, 여기는 쓰기만 한다.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

from pydantic import BaseModel

_SERVER_ROOT = Path(__file__).resolve().parent.parent


def raw_store_dir(configured: str) -> Path:
    """설정된 저장 위치. 상대 경로는 server/ 루트 기준으로 해석한다."""
    path = Path(configured)
    return path if path.is_absolute() else _SERVER_ROOT / path


def store_raw_exchange(
    directory: Path,
    request_id: str,
    status_code: int,
    request: BaseModel,
    response: BaseModel,
) -> Path:
    """왕복 봉투를 파일 하나로 저장하고 경로를 돌려준다. 실패 응답(ApiError)도 봉투 대상이다."""
    stored_at = datetime.now(UTC)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"{stored_at:%Y%m%dT%H%M%S%f}Z_{request_id}.json"
    envelope = {
        "stored_at": stored_at.isoformat(),
        "request_id": request_id,
        "client_version": getattr(request, "client_version", None),
        "status": status_code,
        "request": request.model_dump(mode="json"),
        "response": response.model_dump(mode="json"),
    }
    target.write_text(
        json.dumps(envelope, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8",
    )
    return target
