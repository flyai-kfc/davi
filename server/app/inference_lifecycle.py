"""추론 HTTP 전송의 deadline·공급자 호출 예산·논리 요청 중복 제거 경계(#82).

JSON wire 계약은 바꾸지 않는다. 앱이 보내는 비식별 logical request ID를 인증 범위와 endpoint,
검증된 요청 본문의 digest에 묶어 같은 논리 작업만 합친다. 성공 결과만 짧게 재전송하고 실패는
즉시 버려, retryable 429/502가 같은 ID에 영구 고정되지 않게 한다.

FastAPI endpoint와 공급자 클라이언트가 동기식이므로 연결 종료만으로 안전하게 실행 중 HTTP를
취소할 수 없다. 첫 전송이 끊겨도 재전송이 같은 작업에 합류할 수 있게 작업은 hard deadline까지
계속하고, deadline 뒤 결과는 캐시하지 않는다. 공급자 HTTP는 남은 예산을 timeout으로 받아 그
경계 안에서 끝난다.
"""

from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor, TimeoutError
from contextlib import contextmanager
from contextvars import ContextVar, copy_context
from dataclasses import dataclass
from enum import StrEnum
from threading import Lock

from pydantic import BaseModel

from .errors import LlmError
from .schemas import ErrorKind, GuideStep, NextStepRequest

PROVIDER_CALL_TIMEOUT_SECONDS = 20.0
INFERENCE_DEADLINE_SECONDS = 40.0
SUCCESS_REPLAY_TTL_SECONDS = 120.0
MAX_SUCCESS_REPLAYS = 256
INFERENCE_MAX_WORKERS = 40
_MUTABLE_LAST_STEP_FIELDS = frozenset({"screen_changed", "performed", "content_signature"})
_unknown_mutable_step_fields = _MUTABLE_LAST_STEP_FIELDS - GuideStep.model_fields.keys()
if _unknown_mutable_step_fields:
    unknown = ", ".join(sorted(_unknown_mutable_step_fields))
    raise RuntimeError(f"GuideStep에 없는 재전송 관측 필드입니다: {unknown}")

# schema·계약 복구까지 포함한 실제 공급자 HTTP 호출 상한이다. 호출부가 attempt 번호를 잘못
# 재사용해도 아래 request-scope 카운터가 실제 호출 수를 따로 세어 상한을 지킨다.
PROVIDER_CALL_LIMITS = {
    "next_step": 3,
    "app_candidates": 2,
    "intent_dialogue": 1,
}

_deadline_at: ContextVar[float | None] = ContextVar("inference_deadline_at", default=None)
_provider_call_counts: ContextVar[dict[str, int] | None] = ContextVar(
    "provider_call_counts",
    default=None,
)


class ExecutionRole(StrEnum):
    OWNER = "owner"
    IN_FLIGHT_JOIN = "in_flight_join"
    RESULT_REPLAY = "result_replay"
    UNTRACKED = "untracked"


class LogicalRequestConflict(Exception):
    """같은 인증 범위·endpoint·logical ID에 다른 본문이 들어온 fail-closed 충돌."""


@dataclass(frozen=True)
class _SharedOutcome[T]:
    value: T | None = None
    error: BaseException | None = None


@dataclass(frozen=True)
class CoordinatedExecution[T]:
    """HTTP 전송별 역할과 공유 논리 작업 결과."""

    outcome: _SharedOutcome[T]
    role: ExecutionRole

    @property
    def owns_work(self) -> bool:
        return self.role in {ExecutionRole.OWNER, ExecutionRole.UNTRACKED}

    def unwrap(self) -> T:
        if self.outcome.error is not None:
            raise self.outcome.error
        return self.outcome.value  # type: ignore[return-value]


@dataclass
class _Entry[T]:
    fingerprint: str
    future: Future[_SharedOutcome[T]]
    deadline_at: float
    completed_at: float | None = None


@contextmanager
def inference_budget(deadline_seconds: float = INFERENCE_DEADLINE_SECONDS):
    """논리 작업 하나의 monotonic deadline과 실제 공급자 호출 횟수를 초기화한다."""

    with _inference_budget_until(time.monotonic() + deadline_seconds):
        yield


@contextmanager
def _inference_budget_until(deadline_at: float):
    """대기열에서 보낸 시간도 포함하는 절대 deadline을 request context에 건다."""

    deadline_token = _deadline_at.set(deadline_at)
    calls_token = _provider_call_counts.set({})
    try:
        yield
    finally:
        _provider_call_counts.reset(calls_token)
        _deadline_at.reset(deadline_token)


def provider_call_timeout_seconds(operation: str, declared_attempt: int) -> float:
    """호출 횟수를 소비하고 현재 논리 작업에 남은 공급자 HTTP timeout을 반환한다."""

    limit = PROVIDER_CALL_LIMITS.get(operation)
    if limit is None:
        raise RuntimeError(f"공급자 호출 상한이 없는 operation입니다: {operation}")
    if declared_attempt <= 0 or declared_attempt > limit:
        raise LlmError(ErrorKind.SERVER, f"{operation} 공급자 호출 상한({limit}) 초과")

    counts = _provider_call_counts.get()
    if counts is not None:
        actual_attempt = counts.get(operation, 0) + 1
        counts[operation] = actual_attempt
        if actual_attempt > limit:
            raise LlmError(ErrorKind.SERVER, f"{operation} 공급자 호출 상한({limit}) 초과")
        if actual_attempt != declared_attempt:
            raise LlmError(
                ErrorKind.SERVER,
                f"{operation} 공급자 attempt 순서 불일치",
            )

    deadline_at = _deadline_at.get()
    if deadline_at is None:
        return PROVIDER_CALL_TIMEOUT_SECONDS
    remaining = deadline_at - time.monotonic()
    if remaining <= 0:
        raise LlmError(ErrorKind.NETWORK, "서버 추론 처리 시간이 초과되었습니다")
    return min(PROVIDER_CALL_TIMEOUT_SECONDS, remaining)


def request_fingerprint(request: BaseModel) -> str:
    """원문을 보관하지 않고 논리 요청의 의미상 identity를 digest로 만든다.

    next-step의 재전송 identity는 Android ``ScreenSignature.structure``와 같은 축을 쓴다.
    label·window title·node id·bounds·스크롤 위치 메타와 마지막 step의 앱 관측값은 앱이 같은
    logical ID에서 허용하는 내용/과도기 값이라 제외한다. 그 밖의 endpoint는 검증·기본값 적용 뒤
    본문 전체가 identity다.
    """

    canonical = json.dumps(
        _request_identity(request),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _request_identity(request: BaseModel) -> dict:
    if not isinstance(request, NextStepRequest):
        return request.model_dump(mode="json")

    # 앱은 network/timeout retry에서 화면을 다시 직렬화한다. 구조가 같은 동안에도 시계·배지
    # label, 정착 중 bounds, 직렬화 node id는 달라질 수 있으므로 본문 전체를 비교하면 정상
    # 재전송을 409로 오판한다. 요소 순서·수와 아래 행동 상태는 Android 구조 서명과 같다.
    identity = request.model_dump(mode="json", exclude={"screen", "steps"})
    identity["screen"] = {
        "package_name": request.screen.package_name,
        "current_page": request.screen.current_page,
        "total_pages": request.screen.total_pages,
        "has_scrollable_element": request.screen.has_scrollable_element,
        "ime_visible": request.screen.ime_visible,
        "elements": [
            {
                "role": element.role,
                "clickable": element.clickable,
                "editable": element.editable,
                "focused": element.focused,
                "enabled": element.enabled,
                "checked": element.checked,
                "selected": element.selected,
                "showing_hint_text": element.showing_hint_text,
                "obscured": element.obscured,
            }
            for element in request.screen.elements
        ],
    }
    identity["steps"] = []
    last_step_index = len(request.steps) - 1
    for index, step in enumerate(request.steps):
        step_identity = step.model_dump(mode="json")
        if index == last_step_index:
            for field in _MUTABLE_LAST_STEP_FIELDS:
                step_identity.pop(field, None)
        identity["steps"].append(step_identity)
    return identity


class InferenceCoordinator:
    """프로세스 안에서 동일 logical request의 in-flight 실행과 성공 결과를 공유한다.

    현재 배포 계약은 Uvicorn worker 1개·Azure replica 1개다. 그 불변식이 바뀌면 이 인메모리
    경계를 Redis 같은 공유 저장소로 옮기기 전에는 전역 중복 제거를 주장할 수 없다.
    """

    def __init__(
        self,
        *,
        deadline_seconds: float = INFERENCE_DEADLINE_SECONDS,
        replay_ttl_seconds: float = SUCCESS_REPLAY_TTL_SECONDS,
        max_success_replays: int = MAX_SUCCESS_REPLAYS,
        max_workers: int = INFERENCE_MAX_WORKERS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.deadline_seconds = deadline_seconds
        self.replay_ttl_seconds = replay_ttl_seconds
        self.max_success_replays = max_success_replays
        self.clock = clock
        self._lock = Lock()
        self._entries: dict[tuple[str, str, str], _Entry] = {}
        self._executor = ThreadPoolExecutor(
            max_workers=max_workers,
            thread_name_prefix="davi-inference",
        )

    def execute[T](
        self,
        *,
        scope: str,
        endpoint: str,
        logical_request_id: str | None,
        request: BaseModel,
        work: Callable[[], T],
    ) -> CoordinatedExecution[T]:
        """한 전송을 공유 논리 작업에 연결한다. header 없는 구형 앱은 dedupe 없이 실행한다."""

        if logical_request_id is None:
            deadline_at = time.monotonic() + self.deadline_seconds
            context = copy_context()
            future = self._executor.submit(context.run, self._run_work, work, deadline_at)
            outcome = self._wait_for_outcome(future, deadline_at)
            return CoordinatedExecution(outcome, ExecutionRole.UNTRACKED)

        key = (scope, endpoint, logical_request_id)
        fingerprint = request_fingerprint(request)
        callback: tuple[tuple[str, str, str], _Entry, Future] | None = None
        with self._lock:
            self._prune_locked()
            entry = self._entries.get(key)
            if entry is not None and entry.fingerprint != fingerprint:
                raise LogicalRequestConflict
            if entry is not None and entry.future.done():
                finished = entry.future.result()
                if finished.error is not None:
                    # 실패는 cache가 아니다. 완료 callback과 다음 전송의 경합에서도 새 실행을 연다.
                    self._entries.pop(key, None)
                    entry = None

            if entry is None:
                deadline_at = time.monotonic() + self.deadline_seconds
                context = copy_context()
                future = self._executor.submit(
                    context.run,
                    self._run_work,
                    work,
                    deadline_at,
                )
                entry = _Entry(
                    fingerprint=fingerprint,
                    future=future,
                    deadline_at=deadline_at,
                )
                self._entries[key] = entry
                role = ExecutionRole.OWNER
                callback = (key, entry, future)
            else:
                role = (
                    ExecutionRole.RESULT_REPLAY
                    if entry.future.done()
                    else ExecutionRole.IN_FLIGHT_JOIN
                )
            future = entry.future

        # 이미 끝난 future에 callback을 lock 안에서 붙이면 callback이 즉시 실행되며 같은 lock을
        # 다시 잡아 교착한다. entry 게시 뒤 lock 밖에서 등록한다.
        if callback is not None:
            callback_key, callback_entry, callback_future = callback
            callback_future.add_done_callback(
                lambda done: self._finalize(callback_key, callback_entry, done)
            )

        outcome = self._wait_for_outcome(future, entry.deadline_at)
        return CoordinatedExecution(outcome, role)

    def reset(self) -> None:
        """테스트 사이 성공 replay와 in-flight key를 격리한다."""

        with self._lock:
            self._entries.clear()

    def _run_work[T](self, work: Callable[[], T], deadline_at: float) -> _SharedOutcome[T]:
        with _inference_budget_until(deadline_at):
            try:
                if time.monotonic() >= deadline_at:
                    return self._deadline_failure()
                value = work()
                if time.monotonic() >= deadline_at:
                    return self._deadline_failure()
                return _SharedOutcome(value=value)
            except BaseException as error:  # noqa: BLE001 - waiter에 같은 실패를 전달하는 경계
                return _SharedOutcome(error=error)

    def _wait_for_outcome[T](
        self,
        future: Future[_SharedOutcome[T]],
        deadline_at: float,
    ) -> _SharedOutcome[T]:
        try:
            return future.result(timeout=max(0.0, deadline_at - time.monotonic()))
        except TimeoutError:
            # Python thread를 강제 종료할 수 없다. 공유 작업은 자기 provider timeout까지 마치되,
            # 모든 waiter는 같은 논리 deadline에서 닫고 늦은 성공은 _run_work가 폐기한다.
            return self._deadline_failure()

    @staticmethod
    def _deadline_failure[T]() -> _SharedOutcome[T]:
        return _SharedOutcome(
            error=LlmError(ErrorKind.NETWORK, "서버 추론 처리 시간이 초과되었습니다")
        )

    def _finalize(
        self,
        key: tuple[str, str, str],
        entry: _Entry,
        future: Future[_SharedOutcome],
    ) -> None:
        outcome = future.result()
        with self._lock:
            if self._entries.get(key) is not entry:
                return
            if outcome.error is not None:
                self._entries.pop(key, None)
                return
            entry.completed_at = self.clock()
            self._trim_successes_locked()

    def _prune_locked(self) -> None:
        now = self.clock()
        expired = [
            key
            for key, entry in self._entries.items()
            if entry.completed_at is not None
            and now - entry.completed_at >= self.replay_ttl_seconds
        ]
        for key in expired:
            self._entries.pop(key, None)

    def _trim_successes_locked(self) -> None:
        completed = [
            (entry.completed_at, key)
            for key, entry in self._entries.items()
            if entry.completed_at is not None
        ]
        overflow = len(completed) - self.max_success_replays
        if overflow <= 0:
            return
        for _, key in sorted(completed)[:overflow]:
            self._entries.pop(key, None)
