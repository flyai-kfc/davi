"""CI는 `eval/`을 작업 디렉터리로 pytest를 돌린다 — 그때도 `pingpong_eval`을 찾게 한다.

이 폴더에서 직접 돌릴 때는 필요 없지만, 두 실행 경로가 같은 결과를 내야 회귀가 게이트 노릇을 한다.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))


@pytest.fixture(autouse=True)
def _개발자_env를_테스트에_들이지_않는다(monkeypatch, tmp_path):
    """`cli.main`을 부르는 테스트가 여럿인데, 그때마다 `load_env`가 실제 `.env`를 올린다.

    한 번 올라간 값은 세션 끝까지 남아, 로컬(파일 있음)과 CI(파일 없음)가 다른 환경에서 돈다.
    지금은 키 부재를 단언하는 테스트가 없어 드러나지 않지만 생기는 순간 갈린다.
    **`.env` 로딩 자체를 검사하는 테스트는 경로를 직접 주므로** 이 고정에 걸리지 않는다.
    """
    from pingpong_eval import cli

    monkeypatch.setattr(cli, "ENV_FILE", tmp_path / "없는파일.env")
