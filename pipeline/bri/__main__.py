"""CLI: python -m bri prepare | finalize

prepare  — 날씨를 받아 규칙 엔진으로 평가하고 work/claude_input.json 을 쓴다.
finalize — Claude 출력을 검증해 docs/latest.json 과 history 를 쓴다.
좌표는 환경변수에서만 읽고 어떤 파일에도 쓰지 않는다.
"""
import argparse
import json
import os
import shutil
import sys
from datetime import datetime, timedelta
from pathlib import Path

from . import kma, openmeteo, publish
from .claude_io import build_claude_input, validate_claude_output
from .engine import build_model_windows, evaluate_slot
from .rules import DEFAULT_RULES_PATH, REPO_ROOT, load_rules

HOURLY_VARS = ("temp", "precip", "snowfall", "pty")


def _gh_output(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")


def _bounds(date: str, hhmm: str) -> datetime:
    """창 경계 시각을 시 단위로 내린다(08:30 → 08:00)."""
    d = datetime.fromisoformat(date)
    h, _m = map(int, hhmm.split(":"))
    return d + timedelta(hours=h)


def _hourly(windows: dict, start: datetime, end: datetime) -> dict:
    hours = []
    t = start
    while t <= end:
        hours.append(t.strftime("%H:%M"))
        t += timedelta(hours=1)
    out = {}
    for model, w in windows.items():
        out[model] = {"hours": hours[:len(w["temp"])], **{v: w[v] for v in HOURLY_VARS}}
    return out


def _coords() -> dict[str, tuple[float, float]]:
    try:
        return {"home": (float(os.environ["HOME_LAT"]), float(os.environ["HOME_LON"])),
                "work": (float(os.environ["WORK_LAT"]), float(os.environ["WORK_LON"]))}
    except (KeyError, ValueError) as e:
        raise SystemExit(f"좌표 환경변수가 없거나 잘못됨: {e.__class__.__name__}")


def cmd_prepare(args) -> int:
    rules = load_rules()
    now = publish.kst_now()
    run = publish.resolve_run(now, args.run)
    date = now.date().isoformat()
    docs, work = Path(args.docs), Path(args.work)

    reason = publish.should_skip(docs, date, run, now, rules, args.force)
    if reason:
        print(f"건너뜀: {date} {run} — {reason}")
        _gh_output("skip", "true")
        return 0

    coords = _coords()
    key = os.environ.get("KMA_SERVICE_KEY", "")
    series = {}
    for point, (lat, lon) in coords.items():
        try:
            s = openmeteo.fetch_point(lat, lon, rules["openmeteo_models"])
        except openmeteo.OpenMeteoError as e:
            print(f"Open-Meteo 조회 실패({point}): {e}", file=sys.stderr)
            return 1
        k = kma.fetch_point(lat, lon, key, now, rules["kma_model_name"])
        if k is None:
            print(f"기상청 단기예보 없음({point}) — 이 출처를 빼고 진행", file=sys.stderr)
        else:
            s.append(k)
        series[point] = s

    models_used = []
    for name in rules["openmeteo_models"] + [rules["kma_model_name"]]:
        if all(any(m.model == name for m in ss) for ss in series.values()):
            models_used.append(name)

    state_slots, input_slots = [], []
    for slot_id in publish.slots_for_run(run):
        conf = rules["slots"][slot_id]
        points, hourly = {}, {}
        for point, (a, b) in conf["points"].items():
            start, end = _bounds(date, a), _bounds(date, b)
            windows = build_model_windows(series[point], start, end)
            points[point] = windows
            hourly[point] = _hourly(windows, start, end)
        ev = evaluate_slot(points, rules)
        state_slots.append({"id": slot_id, "time": conf["time"], "eval": ev, "weather_by_model": hourly})
        input_slots.append({"id": slot_id, "time": conf["time"], "eval": ev, "hourly": hourly})

    work.mkdir(parents=True, exist_ok=True)
    (work / "claude_output.json").unlink(missing_ok=True)
    _write_json(work / "state.json", {"date": date, "run": run, "models_used": models_used, "slots": state_slots})
    _write_json(work / "claude_input.json", build_claude_input(date, run, input_slots, rules))
    print(f"준비 완료: {date} {run}, 모델 {len(models_used)}개, 슬롯 {len(state_slots)}개")
    _gh_output("skip", "false")
    return 0


def _read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def cmd_finalize(args) -> int:
    rules = load_rules()
    docs, work = Path(args.docs), Path(args.work)
    state = _read_json(work / "state.json")
    if state is None:
        print("work/state.json 없음 — prepare가 건너뛰었거나 실패함", file=sys.stderr)
        return 1

    ids = [s["id"] for s in state["slots"]]
    rule_scores = {s["id"]: s["eval"]["rule_score"] for s in state["slots"]}
    claude = validate_claude_output(_read_json(work / "claude_output.json"), ids, rule_scores, rules)
    source = "claude" if claude else "rule"

    results = [publish.final_slot(s["id"], state["date"], s["time"], s["eval"],
                                  claude[s["id"]] if claude else None, s["weather_by_model"], rules)
               for s in state["slots"]]
    previous = _read_json(docs / "latest.json")
    generated_at = publish.kst_now().isoformat(timespec="seconds")
    latest = publish.assemble_latest(state["date"], state["run"], generated_at, results, previous,
                                     source, state["models_used"])

    _write_json(docs / "latest.json", latest)
    _write_json(publish.history_path(docs, state["date"], state["run"]), latest)
    shutil.copyfile(DEFAULT_RULES_PATH, docs / "rules.json")

    lines = [f"### 주행 지수 {state['date']} {state['run']} (source: {source})"]
    lines += [f"- {s['id']} {s['time']}: {s['score']}점 {s['grade']} — {s['summary']}" for s in latest["slots"]]
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):  # Windows 콘솔(cp949)에서도 한국어·기호 출력
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(prog="bri")
    sub = p.add_subparsers(dest="cmd", required=True)
    pp = sub.add_parser("prepare")
    pp.add_argument("--work", default=str(REPO_ROOT / "work"))
    pp.add_argument("--docs", default=str(REPO_ROOT / "docs"))
    pp.add_argument("--run", choices=("am", "pm", "auto"), default=None)
    pp.add_argument("--force", action="store_true")
    pf = sub.add_parser("finalize")
    pf.add_argument("--work", default=str(REPO_ROOT / "work"))
    pf.add_argument("--docs", default=str(REPO_ROOT / "docs"))
    args = p.parse_args(argv)
    return cmd_prepare(args) if args.cmd == "prepare" else cmd_finalize(args)


if __name__ == "__main__":
    sys.exit(main())
