#!/usr/bin/env bash

# dumpsys accessibility의 섹션 하나(`라벨:{...}`)를 통째로 잘라낸다. 섹션은 전부 `이름:{` 꼴이라
# 다음 섹션 머리 직전까지가 한 블록이다.
# **한 줄만 보면 안 된다** — 바인딩된 서비스가 둘 이상이면 두 번째부터 줄이 바뀌어서(#302 실측),
# 한 줄 grep은 첫 서비스만 본다. debug/release가 함께 켜진 기기에서 실재하는 조합이다.
#
# **첫 섹션에서 끝낸다**(finished). dumpsys는 `User state[...]`를 사용자별로 찍으므로 직장 프로필·
# 보조 사용자가 있으면 같은 라벨이 여러 번 나온다. 끝내지 않으면 뒤 사용자의 섹션에서 다시 들어가
# 전부 이어 붙고, 보조 사용자에만 붙은 서비스를 우리 것으로 오탐한다. 계측이 도는 것은 현재
# 사용자(=첫 블록)뿐이다. PowerShell·Kotlin 쪽 같은 이름의 함수도 첫 섹션만 본다.
#
# dev-install.sh와 run-device-e2e.sh가 이 함수를 source해 같은 목록·바인딩 판정 근거를 공유한다.
dumpsys_section() {
    awk -v label="$1" '
        finished { next }
        !inside && $0 ~ "^[[:space:]]*" label "[[:space:]]*:[{]" { inside = 1; print; next }
        inside && $0 ~ "^[[:space:]]*[A-Za-z][A-Za-z0-9 ]*:[{]" { inside = 0; finished = 1; next }
        inside { print }
    '
}
