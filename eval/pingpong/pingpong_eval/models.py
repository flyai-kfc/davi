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
    UNABLE_TO_FINALIZE = "unable_to_finalize"


class AnswerSource(StrEnum):
    """서버 계약(#326)의 ``ClarificationAnswerSource``와 값이 같아야 한다."""

    OPTION = "option"
    FREEFORM = "freeform"
    UNKNOWN_OR_DECLINED = "unknown_or_declined"


class Question(BaseModel):
    """앱이 추가 호출 없이 한 화면씩 보여 주는 질문(#326)."""

    id: str
    prompt: str
    options: list[str] = Field(default_factory=list)
    allow_freeform: bool = True


class PingpongTurn(BaseModel):
    """핑퐁이 돌려준 것. 어댑터가 형식 차이를 여기로 흡수한다.

    #326 이전 형식(텍스트 질문 하나)은 ``legacy=True``인 질문 하나로 표현된다 —
    그 형식으로 잰 기준선과 새 형식을 같은 자료형으로 비교하기 위해서다.
    """

    status: PingpongStatus
    message: str = ""
    questions: list[Question] = Field(default_factory=list)
    goal: str | None = None
    legacy: bool = False
    latency_ms: int = 0


class UserAnswer(BaseModel):
    """질문 하나에 대한 사용자 LLM의 답. 그대로 두 번째 요청에 실린다."""

    question_id: str
    prompt: str
    answer: str
    source: AnswerSource
    chosen_option: int | None = None
    revealed_slots: list[str] = Field(default_factory=list)

    def to_wire(self) -> dict[str, str]:
        return {
            "question_id": self.question_id,
            "prompt": self.prompt,
            "answer": self.answer,
            "source": self.source.value,
        }


class Exchange(BaseModel):
    """서버 요청 한 번과, 그 응답의 질문들에 로컬에서 모은 답."""

    index: int
    turn: PingpongTurn
    answers: list[UserAnswer] = Field(default_factory=list)


class StopReason(StrEnum):
    READY = "ready"
    UNABLE = "unable_to_finalize"
    LIMIT = "limit"
    ERROR = "error"


class Transcript(BaseModel):
    scenario_id: str
    opening: str
    exchanges: list[Exchange] = Field(default_factory=list)
    final_goal: str | None = None
    stop_reason: StopReason
    error: str | None = None
    elapsed_ms: int = 0

    @property
    def questions_asked(self) -> int:
        return sum(len(exchange.turn.questions) for exchange in self.exchanges)


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


class JudgeBatch(BaseModel):
    """이 judge가 나온 채점 배치(평가자 모델·시나리오 해시).

    평가자 모델이나 시나리오 파일이 다른 채점을 한 수치로 섞으면 '비교 가능한 수치'라는
    하니스의 계약이 깨진다 — 집계는 같은 배치만 한다. ``JudgeResult``에 두지 않는 이유는
    그 모델이 평가자 LLM의 구조화 출력 스키마로 그대로 나가기 때문이다.
    """

    model_config = ConfigDict(extra="forbid")

    model: str
    scenario_digest: str


class ScenarioResult(BaseModel):
    scenario_id: str
    difficulty: Difficulty
    factors: list[str]
    # 첫 발화에 없어서 핑퐁이 질문으로만 얻을 수 있었던 슬롯. 값은 남기지 않고 키만 남긴다.
    # 최종 goal이 이 자리를 틀리게 채운 비율이 #316의 「누락 정보 환각률」이다.
    # 이 필드가 생기기 전 실행은 최초 발화에서 빠진 슬롯을 복원할 수 없다. 빈 목록은
    # "빠진 슬롯 없음"이라는 관측값이고 None은 "당시 기록하지 않아 측정 불가"다.
    missing_slot_keys: list[str] | None = None
    transcript: Transcript
    judge: JudgeResult | None = None
    # judge와 함께 채워지고 함께 비워진다. judge가 있는데 배치가 없으면 출처 미상이라
    # 집계에서 빠진다(이 필드가 생기기 전의 채점).
    judge_batch: JudgeBatch | None = None
    metrics: dict = Field(default_factory=dict)
