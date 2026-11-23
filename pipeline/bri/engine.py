"""규칙 엔진: 모델별 창 값 → 지점 특성 → 결빙 위험도·기준 점수·신뢰도·안전 하한.

Kotlin 포팅(android/.../engine/RuleEngine.kt)이 같은 결과를 내야 한다.
규칙을 바꾸면 rules/test_vectors 와 두 구현을 함께 고친다.
"""
from datetime import datetime
from statistics import median

from .series import ModelSeries, past_sum, window

WINDOW_VARS = ("temp", "apparent", "dewpoint", "precip", "snowfall", "gust", "surface_temp", "pty")
ICE_ORDER = {"없음": 0, "낮음": 1, "높음": 2}


def _vals(xs):
    return [x for x in (xs or []) if x is not None]


def _min(xs):
    v = _vals(xs)
    return min(v) if v else None


def _max(xs):
    v = _vals(xs)
    return max(v) if v else None


def _median(xs):
    v = _vals(xs)
    return float(median(v)) if v else None


def build_model_windows(series: list[ModelSeries], start: datetime, end: datetime) -> dict[str, dict]:
    out = {}
    for s in series:
        w = window(s, start, end)
        n = len(next(iter(w.values()), []))
        mw = {var: w.get(var, [None] * n) for var in WINDOW_VARS}
        mw["past24_precip"] = past_sum(s, "precip", start, 24)
        mw["past48_snowfall"] = past_sum(s, "snowfall", start, 48)
        out[s.model] = mw
    return out


def _model_reduce(w: dict) -> dict:
    temps, dews = w.get("temp") or [], w.get("dewpoint") or []
    spreads = [t - d for t, d in zip(temps, dews) if t is not None and d is not None]
    snow = any(x > 0 for x in _vals(w.get("snowfall"))) or any(x in (2, 3) for x in _vals(w.get("pty")))
    return {
        "has_temp": bool(_vals(temps)),
        "temp": _min(temps),
        "apparent": _min(w.get("apparent")),
        "surface": _min(w.get("surface_temp")),
        "precip": _max(w.get("precip")),
        "gust": _max(w.get("gust")),
        "spread": min(spreads) if spreads else None,
        "snow": snow,
        "past24": w.get("past24_precip"),
        "past48": w.get("past48_snowfall"),
    }


def point_features(models: dict[str, dict], rules: dict) -> dict:
    reds = [_model_reduce(w) for w in models.values()]
    precips = [r["precip"] for r in reds]
    return {
        "n_models": sum(1 for r in reds if r["has_temp"]),
        "temp_c": _median([r["temp"] for r in reds]),
        "apparent_c": _median([r["apparent"] for r in reds]),
        "spread_c": _median([r["spread"] for r in reds]),
        "surface_c": _median([r["surface"] for r in reds]),
        "precip_median": _median(precips),
        "precip_min": _min(precips),
        "precip_max": _max(precips),
        "precip_models": sum(1 for p in precips if p is not None and p >= rules["wet_precip_mm"]),
        "snow_models": sum(1 for r in reds if r["snow"]),
        "gust_ms": _median([r["gust"] for r in reds]),
        "past24_precip_mm": _median([r["past24"] for r in reds]),
        "past48_snow_cm": _median([r["past48"] for r in reds]),
    }


def _le(a, b):
    return a is not None and a <= b


def _ge(a, b):
    return a is not None and a >= b


def ice_risk(pf: dict, rules: dict) -> str:
    ice = rules["ice"]
    past48 = pf["past48_snow_cm"]
    if past48 is not None and past48 > 0 and (_le(pf["temp_c"], ice["high_temp_max"])
                                              or _le(pf["surface_c"], ice["high_surface_max"])):
        return "높음"
    wet = _ge(pf["past24_precip_mm"], ice["low_precip24_min"]) and _le(pf["temp_c"], ice["low_temp_max"])
    frost = _le(pf["temp_c"], ice["frost_temp_max"]) and _le(pf["spread_c"], ice["frost_spread_max"])
    return "낮음" if (wet or frost) else "없음"


def _pick(a, b, fn):
    vals = [x for x in (a, b) if x is not None]
    return fn(vals) if vals else None


def _combine(f1: dict, f2: dict) -> dict:
    out = {}
    for k in ("precip_median", "precip_max", "snow_models", "precip_models", "gust_ms",
              "past24_precip_mm", "past48_snow_cm"):
        out[k] = _pick(f1[k], f2[k], max)
    for k in ("apparent_c", "temp_c", "n_models", "precip_min", "surface_c", "spread_c"):
        out[k] = _pick(f1[k], f2[k], min)
    return out


def _rule_score(f: dict, ice: str, rules: dict) -> int:
    sc = rules["score"]
    penalty = 0
    pm = f["precip_median"]
    if pm is not None:
        for limit, p in sc["precip_steps"]:
            if limit is None or pm < limit:
                penalty += p
                break
    snow = f["snow_models"] or 0
    if snow >= 2:
        penalty += sc["snow_multi"]
    elif snow == 1:
        penalty += sc["snow_single"]
    if ice == "높음":
        penalty += sc["ice_high"]
    elif ice == "낮음":
        penalty += sc["ice_low"]
    cold = f["apparent_c"] if f["apparent_c"] is not None else f["temp_c"]
    if cold is not None:
        if cold <= sc["very_cold_apparent_c"]:
            penalty += sc["very_cold_penalty"]
        elif cold <= sc["cold_apparent_c"]:
            penalty += sc["cold_penalty"]
    if _ge(f["gust_ms"], sc["gust_ms"]):
        penalty += sc["gust_penalty"]
    return max(0, min(100, 100 - penalty))


def _confidence(f: dict) -> str:
    n = f["n_models"] or 0
    if 0 < (f["snow_models"] or 0) < n:
        return "낮음"
    if 0 < (f["precip_models"] or 0) < n:
        return "보통"
    return "높음"


def _floor(f: dict, ice: str, rules: dict) -> tuple[int | None, str | None]:
    fl = rules["floors"]
    snow = f["snow_models"] or 0
    candidates = []
    if snow >= 2:
        candidates.append((fl["snow_multi"], "snow"))
    if ice == "높음":
        candidates.append((fl["ice_high"], "ice"))
    if ice == "낮음":
        candidates.append((fl["ice_low"], "ice"))
    if snow == 1:
        candidates.append((fl["snow_single"], "snow"))
    if not candidates:
        return None, None
    best = candidates[0]
    for c in candidates[1:]:
        if c[0] < best[0]:
            best = c
    return best


def evaluate_slot(points: dict[str, dict[str, dict]], rules: dict) -> dict:
    feats = {name: point_features(models, rules) for name, models in points.items()}
    ices = {name: ice_risk(pf, rules) for name, pf in feats.items()}
    fs = list(feats.values())
    combined = fs[0]
    for pf in fs[1:]:
        combined = _combine(combined, pf)
    ice = max(ices.values(), key=lambda x: ICE_ORDER[x])
    cap, label = _floor(combined, ice, rules)
    return {
        "features": combined,
        "ice_risk": ice,
        "rule_score": _rule_score(combined, ice, rules),
        "confidence": _confidence(combined),
        "floor_cap": cap,
        "floor_label": label,
    }


def apply_floor(score: int, floor_cap: int | None, floor_label: str | None) -> tuple[int, str | None]:
    if floor_cap is not None and score > floor_cap:
        return floor_cap, floor_label
    return score, None


def grade(score: int, rules: dict) -> str:
    for minimum, label in rules["grades"]:
        if score >= minimum:
            return label
    return rules["grades"][-1][1]
