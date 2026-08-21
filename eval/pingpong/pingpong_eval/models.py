"""시나리오·대화·채점의 자료형.

JSON 키는 한국어(팀이 손으로 쓰는 파일이라)이고 코드 식별자는 영어다. 둘은 alias로 잇는다.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

_KO = ConfigDict(populate_by_name=True, extra="forbid")


class Exposure(StrEnum):
    """슬롯 값을 사용자가 언제 말해 줄 수 있는가."""

    FIRST = "처음"
    ON_ASK = "되물음"
    UNKNOWN = "미상"


class Difficulty(StrEnum):
    EASY = "쉬움"
    NORMAL = "중간"
    HARD = "어려움"


class Slot(BaseModel):
    """목표 문장에서 빠지면 안내를 시작할 수 없는 조각. 진행도의 분모다."""

    model_config = _KO

    key: str = Field(alias="키")
    value: str = Field(alias="값")
    exposure: Exposure = Field(alias="노출")
    user_phrase: str | None = Field(default=None, alias="사용자_표현")


class Scenario(BaseModel):
    model_config = _KO

    id: str
    difficulty: Difficulty = Field(alias="난이도")
    factors: list[str] = Field(default_factory=list, alias="난이도_요인")
    domain: str = Field(alias="도메인")
    reference_goal: str = Field(alias="정답_goal")
    stop_boundary: str | None = Field(default=None, alias="종료_경계")
    slots: list[Slot]
    user_note: str | None = Field(default=None, alias="사용자_메모")

    @property
    def first_slots(self) -> list[Slot]:
        return [s for s in self.slots if s.exposure is Exposure.FIRST]

    @property
    def hidden_slots(self) -> list[Slot]:
        """첫 발화에 절대 나오면 안 되는 슬롯."""

        return [s for s in self.slots if s.exposure is not Exposure.FIRST]


class ScenarioFile(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    version: int
    scenarios: list[Scenario] = Field(alias="시나리오")


class PingpongStatus(StrEnum):
    NEEDS_CLARIFICATION = "needs_clarification"
    READY = "ready"


class PingpongTurn(BaseModel):
    """핑퐁이 돌려준 것. 어댑터가 형식 차이를 여기로 흡수한다.

    옛 형식(텍스트 질문 하나)은 ``options``가 빈 특수 케이스로 표현된다.
    """

    status: PingpongStatus
    question: str = ""
    options: list[str] = Field(default_factory=list)
    has_other: bool = True
    goal: str | None = None
    latency_ms: int = 0


class UserReply(BaseModel):
    """사용자 LLM의 응답."""

    chosen_option: int | None = None
    text: str
    used_other: bool = False
    revealed_slots: list[str] = Field(default_factory=list)


class Round(BaseModel):
    index: int
    pingpong: PingpongTurn
    reply: UserReply | None = None


class StopReason(StrEnum):
    READY = "ready"
    LIMIT = "limit"
    ERROR = "error"


class Transcript(BaseModel):
    scenario_id: str
    opening: str
    rounds: list[Round] = Field(default_factory=list)
    final_goal: str | None = None
    stop_reason: StopReason
    error: str | None = None
    elapsed_ms: int = 0


class SlotVerdict(StrEnum):
    CORRECT = "정확"
    PARTIAL = "부분"
    MISSING = "누락"
    WRONG = "틀림"


class SlotScore(BaseModel):
    """길이 상한은 비용 통제다 — 출력 토큰은 입력의 5배로 과금된다(게이트웨이 실측)."""

    model_config = ConfigDict(extra="forbid")

    key: str
    verdict: SlotVerdict
    evidence: str = Field(default="", description="근거를 짧게 인용")


class ViolationAxis(StrEnum):
    HALLUCINATED_PROPER_NOUN = "고유명_환각"
    UNNECESSARY_QUESTION = "불필요_질문"
    REPEATED_QUESTION = "질문_반복"
    TWO_QUESTIONS_AT_ONCE = "한번에_두개"
    FORCED_CHOICE = "선택_강요"
    APP_JARGON = "앱_전문용어"
    MISSING_OTHER = "기타_없음"


class Violation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    axis: ViolationAxis
    round_index: int
    quote: str = Field(description="문제가 된 문장의 앞부분")
    why: str = Field(description="한 구절")


class Achievement(StrEnum):
    FULL = "충족"
    PARTIAL = "부분"
    FAIL = "실패"


class JudgeResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    achievement: Achievement
    reason: str = Field(description="판정 근거 한 문장")
    slot_scores: list[SlotScore]
    violations: list[Violation]


class ScenarioResult(BaseModel):
    scenario_id: str
    difficulty: Difficulty
    factors: list[str]
    transcript: Transcript
    judge: JudgeResult | None = None
    metrics: dict = Field(default_factory=dict)
