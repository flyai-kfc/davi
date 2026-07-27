from app.errors import LlmError, api_error_for, http_status_for, retry_after_header
from app.schemas import ErrorKind


def test_quota_error_keeps_http_retry_contract():
    error = LlmError(ErrorKind.QUOTA, "quota", retry_after_seconds=0)

    body = api_error_for(error, "request-1")

    assert http_status_for(error) == 429
    assert body.kind is ErrorKind.QUOTA
    assert body.request_id == "request-1"
    # 즉시 재시도 가능한 0도 기본값으로 바꾸지 않는다.
    assert retry_after_header(error) == {"Retry-After": "0"}


def test_non_quota_error_uses_gateway_status_without_retry_header():
    error = LlmError(ErrorKind.NETWORK, "offline")

    assert http_status_for(error) == 502
    assert retry_after_header(error) == {}
