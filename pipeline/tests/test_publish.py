import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from bri.engine import evaluate_slot
from bri.publish import assemble_latest, final_slot, resolve_run, should_skip, slots_for_run, window_bounds
from bri.rules import REPO_ROOT, load_rules

MODELS = ["ecmwf_ifs025", "kma_vilage"]


def vec(name):
    v = json.loads((REPO_ROOT / "rules" / "test_vectors" / f"{name}.json").read_text(encoding="utf-8"))
    return evaluate_slot(v["points"], load_rules())


def slot(slot_id, date, name="a_dry_mild", claude=None):
    time = "08:30" if slot_id == "commute_in" else "18:30"
    return final_slot(slot_id, date, time, vec(name), claude, {}, load_rules())


class TestPublish(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.docs = Path(self.tmp.name) / "docs"
        self.docs.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_resolve_run_and_kst_date(self):
        utc = datetime(2026, 11, 22, 21, 30, tzinfo=timezone.utc)  # 일요일 21:30 UTC
        k = utc.astimezone(ZoneInfo("Asia/Seoul"))
        self.assertEqual((k.date().isoformat(), resolve_run(k, None)), ("2026-11-23", "am"))
        self.assertEqual(resolve_run(datetime(2026, 11, 24, 17, 3), None), "pm")
        self.assertEqual(resolve_run(datetime(2026, 11, 24, 17, 3), "am"), "am")
        self.assertEqual(resolve_run(datetime(2026, 11, 24, 6, 3), "auto"), "am")

    def test_window_bounds_round_end_up(self):
        # I2: 강수·적설은 직전 1시간 합이다. 끝 시각은 올림해 마지막 30분을 포함한다
        self.assertEqual(window_bounds("2026-11-24", "08:30", "09:30"),
                         (datetime(2026, 11, 24, 8), datetime(2026, 11, 24, 10)))
        self.assertEqual(window_bounds("2026-11-24", "08:00", "09:00"),
                         (datetime(2026, 11, 24, 8), datetime(2026, 11, 24, 9)))

    def test_slots_for_run(self):
        self.assertEqual(slots_for_run("am"), ["commute_in", "commute_out"])
        self.assertEqual(slots_for_run("pm"), ["commute_out"])

    def test_skip_weekend_and_duplicate(self):
        sat = datetime(2026, 11, 28, 6, 30)
        self.assertIsNotNone(should_skip(self.docs, "2026-11-28", "am", sat, load_rules(), False))
        (self.docs / "history").mkdir(parents=True)
        (self.docs / "history" / "2026-11-24-am.json").write_text("{}")
        tue = datetime(2026, 11, 24, 6, 30)
        self.assertIsNotNone(should_skip(self.docs, "2026-11-24", "am", tue, load_rules(), False))
        self.assertIsNone(should_skip(self.docs, "2026-11-24", "am", tue, load_rules(), True))
        self.assertIsNone(should_skip(self.docs, "2026-11-24", "pm", tue, load_rules(), False))

    def test_final_slot_floor_overrides_claude(self):
        claude = {"score": 80, "confidence": "보통", "summary": "s", "reasons": ["r"], "deviation_reason": "x"}
        s = slot("commute_in", "2026-11-24", "c_ice_after_snow", claude)
        self.assertEqual((s["score"], s["floor_applied"], s["grade"], s["rule_score"]), (39, "ice", "타지 않음", 40))
        self.assertEqual(s["summary"], "s")

    def test_final_slot_without_claude_uses_rule(self):
        s = slot("commute_in", "2026-11-24", "b_light_rain_cold")
        self.assertEqual((s["score"], s["rule_score"], s["grade"], s["confidence"]), (80, 80, "좋음", "보통"))
        self.assertTrue(s["summary"])
        self.assertLessEqual(len(s["summary"]), 60)
        w = s["weather"]
        self.assertEqual(set(w), {"temp_c", "apparent_c", "precip_mm_h", "snow_models", "ice_risk", "gust_ms"})
        self.assertEqual(w["precip_mm_h"], {"min": 0.0, "max": 0.8})

    def test_pm_without_today_am(self):
        prev = {"slots": [slot("commute_in", "2026-11-23")]}
        out = assemble_latest("2026-11-24", "pm", "2026-11-24T17:03:00+09:00",
                              [slot("commute_out", "2026-11-24")], prev, "claude", MODELS)
        self.assertEqual([s["id"] for s in out["slots"]], ["commute_out"])

    def test_pm_carries_today_am(self):
        prev = {"slots": [slot("commute_in", "2026-11-24"), slot("commute_out", "2026-11-24")]}
        out = assemble_latest("2026-11-24", "pm", "2026-11-24T17:03:00+09:00",
                              [slot("commute_out", "2026-11-24", "b_light_rain_cold")], prev, "claude", MODELS)
        self.assertEqual([s["id"] for s in out["slots"]], ["commute_in", "commute_out"])
        self.assertEqual(out["slots"][1]["score"], 80)  # 새 퇴근 슬롯

    def test_latest_shape_and_no_coordinates(self):
        out = assemble_latest("2026-11-24", "am", "2026-11-24T06:34:00+09:00",
                              [slot("commute_in", "2026-11-24"), slot("commute_out", "2026-11-24")],
                              None, "rule", MODELS)
        self.assertEqual(set(out), {"generated_at", "run", "source", "models_used", "slots"})
        text = json.dumps(out, ensure_ascii=False)
        for word in ("lat", "lon", "nx", "ny"):
            self.assertNotIn(f'"{word}"', text)


if __name__ == "__main__":
    unittest.main()
