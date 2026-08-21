"""CI는 `eval/`을 작업 디렉터리로 pytest를 돌린다 — 그때도 `pingpong_eval`을 찾게 한다.

이 폴더에서 직접 돌릴 때는 필요 없지만, 두 실행 경로가 같은 결과를 내야 회귀가 게이트 노릇을 한다.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
