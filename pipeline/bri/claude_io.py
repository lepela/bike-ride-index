"""Claude 입력 파일 작성과 출력 검증. 입력에는 좌표를 넣지 않는다."""

CONFIDENCE = ("높음", "보통", "낮음")
SLOT_KEYS = {"id", "score", "confidence", "summary", "reasons", "deviation_reason"}


def build_claude_input(date: str, run: str, slots: list[dict], rules: dict) -> dict:
    rubric = {k: rules[k] for k in ("score", "ice", "floors", "grades", "claude")}
    out_slots = []
    for s in slots:
        ev = s["eval"]
        out_slots.append({
            "id": s["id"],
            "time": s["time"],
            "rule_score": ev["rule_score"],
            "ice_risk": ev["ice_risk"],
            "confidence": ev["confidence"],
            "floor_cap": ev["floor_cap"],
            "floor_label": ev["floor_label"],
            "features": ev["features"],
            "hourly": s["hourly"],
        })
    return {"date": date, "run": run, "rubric": rubric, "slots": out_slots}


def _valid_slot(s: object, rule_scores: dict[str, int], max_dev: int) -> bool:
    if not isinstance(s, dict) or set(s) != SLOT_KEYS:
        return False
    if not isinstance(s["id"], str) or s["id"] not in rule_scores:
        return False
    score = s["score"]
    if not isinstance(score, int) or isinstance(score, bool) or not 0 <= score <= 100:
        return False
    if s["confidence"] not in CONFIDENCE:
        return False
    if not isinstance(s["summary"], str) or not 1 <= len(s["summary"].strip()) or len(s["summary"]) > 60:
        return False
    reasons = s["reasons"]
    if not isinstance(reasons, list) or not 1 <= len(reasons) <= 4 or not all(isinstance(r, str) for r in reasons):
        return False
    dev = s["deviation_reason"]
    if dev is not None and not isinstance(dev, str):
        return False
    if abs(score - rule_scores[s["id"]]) > max_dev and not (dev and dev.strip()):
        return False
    return True


def validate_claude_output(out: object, slot_ids: list[str], rule_scores: dict[str, int],
                           rules: dict) -> dict[str, dict] | None:
    if not isinstance(out, dict) or set(out) != {"slots"} or not isinstance(out["slots"], list):
        return None
    max_dev = rules["claude"]["max_deviation_without_reason"]
    slots = out["slots"]
    if not all(_valid_slot(s, rule_scores, max_dev) for s in slots):
        return None
    ids = [s["id"] for s in slots]
    if sorted(ids) != sorted(slot_ids) or len(set(ids)) != len(ids):
        return None
    return {s["id"]: {k: s[k] for k in ("score", "confidence", "summary", "reasons", "deviation_reason")}
            for s in slots}
