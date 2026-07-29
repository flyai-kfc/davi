"""테스트를 수집하기 전에 `server`를 import 경로에 올린다.

경로를 실제로 손보는 곳은 `harness.py`다. 다만 그건 **harness를 import한 뒤**의 이야기라,
harness를 아직 부르지 않은 테스트 모듈이 알파벳순으로 먼저 수집되면 `app`을 찾지 못한다
(#54에서 `test_completion_verify.py`를 추가하다 실제로 겪었다).

conftest는 수집보다 먼저 로드되므로 여기서 한 번 당겨두면 파일 이름 순서에 기대지 않아도 된다.
"""

import harness  # noqa: F401  — sys.path를 손보는 부작용 때문에 import한다
