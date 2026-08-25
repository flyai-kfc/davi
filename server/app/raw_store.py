"""도그푸딩 기기의 요청·응답 봉투 보존(#41) — 운영 로그와 별개 채널.

수신 그대로(1차 마스킹 후)의 요청과 서버가 돌려준 응답을 JSON 한 파일로, 서버가 도는
머신에 영구 보존한다(자동 삭제 없음, 정리는 수동). 화이트리스트 판정과 best-effort
처리는 호출부(main)가 맡고, 여기는 쓰기만 한다.

**봉투의 label에는 패턴 밖 고유명(사람 이름·기기명·대화방 제목)이 그대로 남는다.** 1차
마스킹이 패턴만 지우기 때문이고, 그것을 알고도 그대로 두기로 한 결정이다(2026-08-25, #375)
— 지우면 "실제로 무엇이 들어오는지 본다"는 raw의 목적이 사라진다. 대신 걸린 전제가 **저장
대상이 팀 기기뿐**이라는 것이라, 대상을 넓히려면 `SPEC.md` §3 「대상을 넓히려면」을 먼저
읽는다 — 앱 동의 문구와 개인정보처리방침이 함께 걸린다.
"""

from __future__ import annotations

import json
import os
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
    # 접근 범위는 각 머신의 소유자다(SPEC §3). 이미 있던 넓은 권한도 매번 보정한다.
    # Windows에는 POSIX mode 비트가 없어 사실상 무시되지만 무해하다.
    os.chmod(directory, 0o700)
    target = directory / f"{stored_at:%Y%m%dT%H%M%S%f}Z_{request_id}.json"
    envelope = {
        "stored_at": stored_at.isoformat(),
        "request_id": request_id,
        "client_version": getattr(request, "client_version", None),
        "status": status_code,
        "request": request.model_dump(mode="json"),
        "response": response.model_dump(mode="json"),
    }
    # 파일은 생성 시점부터 소유자 전용(0600)이어야 한다 — 쓰고 나서 조이면 틈이 생긴다.
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(envelope, ensure_ascii=False, indent=1) + "\n")
    return target
