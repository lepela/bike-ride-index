"""모델별 시간 시계열과 창 자르기 도구."""
from dataclasses import dataclass, field
from datetime import datetime, timedelta


@dataclass
class ModelSeries:
    """한 지점·한 모델의 시간별 값. times는 KST naive datetime."""
    model: str
    times: list[datetime]
    values: dict[str, list[float | None]] = field(default_factory=dict)


def window(series: ModelSeries, start: datetime, end: datetime) -> dict[str, list[float | None]]:
    """start <= t <= end 인 시각의 값만 변수별로 돌려준다."""
    idx = [i for i, t in enumerate(series.times) if start <= t <= end]
    return {var: [vals[i] for i in idx] for var, vals in series.values.items()}


def past_sum(series: ModelSeries, var: str, before: datetime, hours: int) -> float | None:
    """[before - hours, before) 구간 합.

    시계열이 그 구간의 시작을 덮지 못하면 None 이다. 기상청 단기예보처럼 발표 이후 값만 있는
    출처의 과거 합을 0으로 만들어 결빙 판정을 지우지 않기 위해서다. 값이 하나도 없어도 None.
    """
    vals = series.values.get(var)
    start = before - timedelta(hours=hours)
    if vals is None or not series.times or series.times[0] > start:
        return None
    picked = [v for t, v in zip(series.times, vals) if start <= t < before and v is not None]
    if not picked:
        return None
    return float(sum(picked))
