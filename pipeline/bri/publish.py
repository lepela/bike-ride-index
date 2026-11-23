"""실행 판정, 멱등 확인, 게시 JSON 조립."""
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from .engine import apply_floor, grade

KST = ZoneInfo("Asia/Seoul")
SLOT_ORDER = ("commute_in", "commute_out")


def kst_now() -> datetime:
    return datetime.now(KST)


def resolve_run(now_kst: datetime, override: str | None) -> str:
    if override in ("am", "pm"):
        return override
    return "am" if now_kst.hour < 12 else "pm"


def slots_for_run(run: str) -> list[str]:
    return ["commute_in", "commute_out"] if run == "am" else ["commute_out"]


def history_path(docs: Path, date: str, run: str) -> Path:
    return Path(docs) / "history" / f"{date}-{run}.json"


def should_skip(docs: Path, date: str, run: str, now_kst: datetime, rules: dict, force: bool) -> str | None:
    if force:
        return None
    if now_kst.isoweekday() not in rules["weekdays"]:
        return "주말"
    if history_path(docs, date, run).exists():
        return "이미 게시됨"
    return None


def _round(x, nd=1):
    return None if x is None else round(float(x), nd)


def rule_summary(ev: dict) -> str:
    f = ev["features"]
    parts = []
    pm = f.get("precip_median")
    parts.append("강수 없음" if not pm else f"강수 {pm:.1f}mm/h")
    if f.get("snow_models"):
        parts.append(f"적설 예보 모델 {f['snow_models']}개")
    parts.append(f"결빙 {ev['ice_risk']}")
    t = f.get("apparent_c") if f.get("apparent_c") is not None else f.get("temp_c")
    if t is not None:
        parts.append(f"체감 {t:.0f}°C")
    return " · ".join(parts)[:60]


def rule_reasons(ev: dict) -> list[str]:
    f = ev["features"]
    reasons = [f"규칙 기준 점수 {ev['rule_score']}점"]
    if f.get("precip_max") is not None:
        reasons.append(f"모델별 최대 강수 {f.get('precip_min') or 0:.1f}~{f['precip_max']:.1f}mm/h")
    reasons.append(f"결빙 위험 {ev['ice_risk']}")
    return reasons


def final_slot(slot_id: str, date: str, time: str, ev: dict, claude: dict | None,
               weather_by_model: dict, rules: dict) -> dict:
    raw = claude["score"] if claude else ev["rule_score"]
    score, floor_applied = apply_floor(raw, ev["floor_cap"], ev["floor_label"])
    f = ev["features"]
    return {
        "id": slot_id,
        "date": date,
        "time": time,
        "score": score,
        "grade": grade(score, rules),
        "confidence": claude["confidence"] if claude else ev["confidence"],
        "summary": claude["summary"] if claude else rule_summary(ev),
        "reasons": claude["reasons"] if claude else rule_reasons(ev),
        "deviation_reason": claude["deviation_reason"] if claude else None,
        "rule_score": ev["rule_score"],
        "floor_applied": floor_applied,
        "weather": {
            "temp_c": _round(f.get("temp_c")),
            "apparent_c": _round(f.get("apparent_c")),
            "precip_mm_h": {"min": _round(f.get("precip_min")), "max": _round(f.get("precip_max"))},
            "snow_models": f.get("snow_models") or 0,
            "ice_risk": ev["ice_risk"],
            "gust_ms": _round(f.get("gust_ms")),
        },
        "weather_by_model": weather_by_model,
    }


def assemble_latest(date: str, run: str, generated_at: str, slot_results: list[dict],
                    previous: dict | None, source: str, models_used: list[str]) -> dict:
    by_id = {s["id"]: s for s in slot_results}
    if run == "pm" and previous:
        for s in previous.get("slots", []):
            if s.get("id") == "commute_in" and s.get("date") == date and "commute_in" not in by_id:
                by_id["commute_in"] = s
    slots = [by_id[i] for i in SLOT_ORDER if i in by_id]
    return {
        "generated_at": generated_at,
        "run": run,
        "source": source,
        "models_used": models_used,
        "slots": slots,
    }
