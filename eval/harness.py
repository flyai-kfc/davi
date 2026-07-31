"""API 키 없이 실행되는 fixture 회귀 하니스.

``fixtures/*.json``을 읽어 다음 세 가지를 항상 확인한다.

1. fixture 전체에 앱/서버 마스킹 규칙상 PII나 ``•••`` 치환 흔적이 없는가
2. ``input``이 최신 ``NextStepRequest`` 계약에 맞고 필드 손실 없이 보존되는가
3. 스텁 LLM을 사용한 추론 파이프라인이 fixture별 기대 결과까지 실행되는가

스텁은 첫 clickable 요소를 고를 뿐이다. 따라서 실기기에서 Gemini가 고른 target이나 안내문을
스텁 exact-match 기대값으로 사용하지 않는다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

# 서버 패키지를 import 경로에 추가(모노레포 형제 폴더).
_SERVER = Path(__file__).resolve().parent.parent / "server"
sys.path.insert(0, str(_SERVER))

from app.errors import LlmError
from app.llm import StubLlmClient
from app.masking import mask_text
from app.reasoning import next_step
from app.schemas import (
    ActionType,
    ErrorKind,
    NextStepRequest,
    NextStepResponse,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures"
_MASK_TOKEN = "•••"
_SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
_SAFE_PATH_KEY = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
_REDACTED_KEY = "<redacted-key>"
_SECRET_PATTERNS = (
    re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b"),
    re.compile(r"\bsk-[0-9A-Za-z_-]{20,}\b"),
)
_FORBIDDEN_KEYS = {
    "api_key",
    "device_id",
    "gemini_api_key",
    "model_response",
    "raw_model_response",
}


class FixtureError(ValueError):
    """원문 값을 노출하지 않는 fixture 오류."""


class ObservedModelAction(BaseModel):
    """실호출에서 관측한 구조화 메타데이터. 스텁 기대값이 아니다."""

    model_config = ConfigDict(extra="forbid")

    action_type: ActionType
    target_node_id: str | None = None
    instruction: str | None = None


class FixtureSource(BaseModel):
    """익명화 fixture의 출처와 사람이 확인할 관측 메타데이터."""

    model_config = ConfigDict(extra="forbid")

    kind: Literal["anonymized_real_device"]
    captured_at: date
    issue: int = Field(gt=0)
    device_model: str
    os_version: str
    ui_version: str | None = None
    anonymization: list[str] = Field(default_factory=list)
    observed_model_action: ObservedModelAction | None = None


class FixtureExpected(BaseModel):
    """스텁으로 주장할 수 있는 파이프라인 수준 기대값."""

    model_config = ConfigDict(extra="forbid")

    stub_outcome: Literal["success", "error"] = "success"
    action_type: ActionType | None = None
    error_kind: ErrorKind | None = None

    @model_validator(mode="after")
    def validate_outcome_fields(self) -> FixtureExpected:
        if self.stub_outcome == "success" and self.error_kind is not None:
            raise ValueError("success 기대값에는 error_kind를 쓸 수 없다")
        if self.stub_outcome == "error" and self.action_type is not None:
            raise ValueError("error 기대값에는 action_type을 쓸 수 없다")
        return self


class FixtureDocument(BaseModel):
    """파일 하나의 envelope. 실제 API 입력은 ``input``에 그대로 둔다."""

    model_config = ConfigDict(extra="forbid")

    id: str
    description: str
    source: FixtureSource
    input: dict[str, Any]
    expected: FixtureExpected = Field(default_factory=FixtureExpected)

    @model_validator(mode="after")
    def validate_id(self) -> FixtureDocument:
        if not _SAFE_ID.fullmatch(self.id):
            raise ValueError("id는 소문자 영숫자, 밑줄, 하이픈만 쓸 수 있다")
        return self


@dataclass(frozen=True)
class LoadedFixture:
    path: Path
    document: FixtureDocument
    request: NextStepRequest


def run_fixture(payload: dict[str, Any]) -> dict[str, Any]:
    """API 요청 payload 하나를 스텁 추론 파이프라인에 넣는다."""

    req = NextStepRequest.model_validate(payload)
    resp = next_step(req, StubLlmClient())
    return resp.model_dump(mode="json")


def find_sensitive_paths(
    value: Any,
    *,
    allowed_iso_date_paths: set[str] | None = None,
) -> list[str]:
    """fixture JSON 전체에서 민감한 key와 값의 안전한 경로만 찾는다.

    ISO 날짜는 호출자가 스키마 검증 결과로 확인한 날짜 필드에서만 예외로 허용한다.
    반환값과 예외에는 실제 값을 절대 포함하지 않는다.
    """

    findings: list[str] = []
    allowed_dates = allowed_iso_date_paths or set()

    def visit(current: Any, path: str) -> None:
        if isinstance(current, dict):
            for key, child in current.items():
                child_path = _child_path(path, key)
                if key.lower() in _FORBIDDEN_KEYS or _is_sensitive_text(key):
                    findings.append(child_path)
                visit(child, child_path)
            return
        if isinstance(current, list):
            for index, child in enumerate(current):
                visit(child, f"{path}[{index}]")
            return
        if not isinstance(current, str):
            return
        # 계좌번호 정규식은 YYYY-MM-DD도 잡는다. 경로 이름이 아니라 실제 스키마의 date 타입으로
        # 확인된 필드만 예외로 둬서 일반 문자열 필드의 날짜 모양 PII를 놓치지 않는다.
        if path in allowed_dates and _ISO_DATE.fullmatch(current):
            return
        if _is_sensitive_text(current):
            findings.append(path)

    visit(value, "$")
    return findings


def assert_no_sensitive_data(
    value: Any,
    *,
    filename: str,
    allowed_iso_date_paths: set[str] | None = None,
) -> None:
    """민감한 원문을 숨긴 채 PII 검사 실패를 보고한다."""

    paths = sorted(
        set(
            find_sensitive_paths(
                value,
                allowed_iso_date_paths=allowed_iso_date_paths,
            )
        )
    )
    if not paths:
        return
    joined = ", ".join(paths)
    raise FixtureError(
        f"{filename}: 민감 데이터 또는 마스킹 토큰 발견: {joined} (원문 생략)"
    )


def load_fixture(path: Path) -> LoadedFixture:
    """JSON 파일 하나를 읽고 PII/envelope/API 계약/입력 보존을 검증한다."""

    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise FixtureError(f"{path.name}: JSON을 읽을 수 없음 (원문 생략)") from error

    try:
        document = FixtureDocument.model_validate(raw)
    except ValidationError as error:
        locations = _validation_locations(error)
        raise FixtureError(
            f"{path.name}: fixture 형식 오류: {locations} (입력값 생략)"
        ) from error

    try:
        request = NextStepRequest.model_validate(document.input)
    except ValidationError as error:
        locations = _validation_locations(error)
        raise FixtureError(
            f"{path.name}: NextStepRequest 스키마 오류: {locations} (입력값 생략)"
        ) from error

    # PII 스캔은 원본 JSON을 대상으로 하되, 날짜 예외는 검증된 모델의 실제 date 값에서 도출한다.
    # FixtureDocument.input은 raw dict이므로 최신 요청 모델의 typed dump로 바꿔 함께 순회한다.
    typed_fixture = document.model_dump(mode="python")
    typed_fixture["input"] = request.model_dump(mode="python")
    assert_no_sensitive_data(
        raw,
        filename=path.name,
        allowed_iso_date_paths=_date_paths(typed_fixture),
    )

    # Pydantic의 기본 동작은 알 수 없는 필드를 무시하고 일부 타입을 coercion한다. fixture가 조용히
    # 손실되지 않도록, 사용자가 실제로 적은 필드만 JSON 모드로 왕복해 바이트 타입까지 비교한다.
    normalized = request.model_dump(mode="json", exclude_unset=True)
    if _canonical_json(document.input) != _canonical_json(normalized):
        raise FixtureError(
            f"{path.name}: input이 현재 스키마 왕복에서 보존되지 않음 "
            "(알 수 없는 필드 또는 타입 변환, 입력값 생략)"
        )

    return LoadedFixture(path=path, document=document, request=request)


def load_fixtures(fixtures_dir: Path = FIXTURES) -> list[LoadedFixture]:
    """디렉터리의 모든 JSON fixture를 이름순으로 읽는다."""

    paths = sorted(fixtures_dir.glob("*.json"))
    if not paths:
        raise FixtureError(f"{fixtures_dir}: JSON fixture가 없음")

    loaded = [load_fixture(path) for path in paths]
    duplicates = sorted(
        fixture_id
        for fixture_id in {fixture.document.id for fixture in loaded}
        if sum(item.document.id == fixture_id for item in loaded) > 1
    )
    if duplicates:
        raise FixtureError(f"중복 fixture id: {', '.join(duplicates)}")
    return loaded


def run_loaded_fixture(fixture: LoadedFixture) -> dict[str, Any]:
    """fixture 하나를 실행하고 민감 원문 없는 결과를 반환한다."""

    expected = fixture.document.expected
    base = {
        "id": fixture.document.id,
        "file": fixture.path.name,
    }
    try:
        response = run_fixture(fixture.document.input)
        NextStepResponse.model_validate(response)
    except LlmError as error:
        if expected.stub_outcome != "error":
            return {
                **base,
                "status": "failed",
                "error": "스텁이 예상하지 않은 분류 오류를 반환함 (상세 생략)",
                "stub": {"outcome": "error", "kind": error.kind.value},
            }
        if expected.error_kind is not None and error.kind is not expected.error_kind:
            return {
                **base,
                "status": "failed",
                "error": "스텁 오류 종류가 기대값과 다름 (상세 생략)",
                "stub": {"outcome": "error", "kind": error.kind.value},
            }
        return {
            **base,
            "status": "passed",
            "checks": [
                "pii",
                "fixture_schema",
                "request_schema",
                "input_preserved",
                "stub",
            ],
            "stub": {"outcome": "error", "kind": error.kind.value},
        }
    except (KeyError, TypeError, ValidationError):
        return {
            **base,
            "status": "failed",
            "error": "스텁 실행 중 예상하지 않은 오류 발생 (상세 생략)",
        }

    action_type = response["action"]["action_type"]
    if expected.stub_outcome != "success":
        return {
            **base,
            "status": "failed",
            "error": "스텁이 오류 대신 성공 응답을 반환함",
            "stub": {"outcome": "success", "action_type": action_type},
        }
    if expected.action_type is not None and action_type != expected.action_type.value:
        return {
            **base,
            "status": "failed",
            "error": "스텁 action_type이 기대값과 다름",
            "stub": {"outcome": "success", "action_type": action_type},
        }
    return {
        **base,
        "status": "passed",
        "checks": [
            "pii",
            "fixture_schema",
            "request_schema",
            "input_preserved",
            "stub",
        ],
        "stub": {"outcome": "success", "action_type": action_type},
    }


def run_suite(fixtures_dir: Path = FIXTURES) -> dict[str, Any]:
    """모든 파일을 독립 실행해 fixture별 결과와 전체 요약을 만든다."""

    paths = sorted(fixtures_dir.glob("*.json"))
    if not paths:
        return {
            "fixtures": [],
            "summary": {"total": 0, "passed": 0, "failed": 1},
            "error": "JSON fixture가 없음",
        }

    results: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for path in paths:
        try:
            fixture = load_fixture(path)
            if fixture.document.id in seen_ids:
                raise FixtureError(f"{path.name}: 중복 fixture id (값 생략)")
            seen_ids.add(fixture.document.id)
            results.append(run_loaded_fixture(fixture))
        except FixtureError as error:
            results.append(
                {
                    "id": path.stem,
                    "file": path.name,
                    "status": "failed",
                    "error": str(error),
                }
            )

    passed = sum(result["status"] == "passed" for result in results)
    return {
        "fixtures": results,
        "summary": {
            "total": len(results),
            "passed": passed,
            "failed": len(results) - passed,
        },
    }


def _validation_locations(error: ValidationError) -> str:
    locations: list[str] = []
    for item in error.errors(
        include_url=False, include_context=False, include_input=False
    ):
        path = "$"
        for part in item["loc"]:
            if isinstance(part, int):
                path = f"{path}[{part}]"
            else:
                path = _child_path(path, str(part))
        locations.append(path)
    return ", ".join(sorted(set(locations))) or "$"


def _child_path(path: str, key: str) -> str:
    """검사 결과에 JSON key 원문이 새지 않는 자식 경로를 만든다."""

    if not _SAFE_PATH_KEY.fullmatch(key) or _is_sensitive_text(key):
        return f"{path}.{_REDACTED_KEY}"
    return f"{path}.{key}"


def _date_paths(value: Any) -> set[str]:
    """검증된 Python 값에서 date 타입인 JSON 경로만 모은다."""

    paths: set[str] = set()

    def visit(current: Any, path: str) -> None:
        if isinstance(current, date):
            paths.add(path)
            return
        if isinstance(current, dict):
            for key, child in current.items():
                visit(child, _child_path(path, key))
            return
        if isinstance(current, list):
            for index, child in enumerate(current):
                visit(child, f"{path}[{index}]")

    visit(value, "$")
    return paths


def _is_sensitive_text(value: str) -> bool:
    return (
        _MASK_TOKEN in value
        or mask_text(value) != value
        or any(pattern.search(value) for pattern in _SECRET_PATTERNS)
    )


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fixtures-dir",
        type=Path,
        default=FIXTURES,
        help="JSON fixture 디렉터리(기본: eval/fixtures)",
    )
    args = parser.parse_args(argv)

    report = run_suite(args.fixtures_dir)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if report["summary"]["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
