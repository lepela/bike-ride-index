import json
import unittest

from bri.claude_io import build_claude_input, validate_claude_output
from bri.engine import evaluate_slot
from bri.rules import REPO_ROOT, load_rules

GOOD = {"slots": [{"id": "commute_in", "score": 70, "confidence": "보통", "summary": "약한 비",
                   "reasons": ["3개 모델 약한 비"], "deviation_reason": None}]}


def sample_slot():
    v = json.loads((REPO_ROOT / "rules" / "test_vectors" / "b_light_rain_cold.json").read_text(encoding="utf-8"))
    return {"id": "commute_in", "time": "08:30", "eval": evaluate_slot(v["points"], load_rules()),
            "hourly": {"home": {"icon_seamless": {"hours": ["08:00", "09:00"], "temp": [4, 4], "precip": [0, 0],
                                                  "snowfall": [0, 0], "pty": [None, None]}}}}


class TestClaudeIo(unittest.TestCase):
    def test_valid(self):
        r = validate_claude_output(GOOD, ["commute_in"], {"commute_in": 75}, load_rules())
        self.assertEqual(r["commute_in"]["score"], 70)

    def test_deviation_with_reason_ok(self):
        out = {"slots": [dict(GOOD["slots"][0], score=50, deviation_reason="두 모델이 진눈깨비")]}
        self.assertIsNotNone(validate_claude_output(out, ["commute_in"], {"commute_in": 75}, load_rules()))

    def test_invalid_outputs_rejected(self):
        bad = [
            {"slots": [dict(GOOD["slots"][0], score="70")]},
            {"slots": [dict(GOOD["slots"][0], score=True)]},
            {"slots": [dict(GOOD["slots"][0], score=101)]},
            {"slots": [dict(GOOD["slots"][0], confidence="high")]},
            {"slots": [dict(GOOD["slots"][0], summary="")]},
            {"slots": [dict(GOOD["slots"][0], summary="가" * 61)]},
            {"slots": [dict(GOOD["slots"][0], reasons=[])]},
            {"slots": [dict(GOOD["slots"][0], reasons=["a", "b", "c", "d", "e"])]},
            {"slots": []},
            {"slots": [GOOD["slots"][0], dict(GOOD["slots"][0], id="extra")]},
            {"slots": [dict(GOOD["slots"][0], score=50)]},  # 25점 차이, 이유 없음
            {"slots": [dict(GOOD["slots"][0], score=50, deviation_reason="  ")]},
            {"slots": [dict(GOOD["slots"][0], extra_key=1)]},
            {"slots": GOOD["slots"], "note": "x"},
            "not json object",
        ]
        for b in bad:
            with self.subTest(b=b):
                self.assertIsNone(validate_claude_output(b, ["commute_in"], {"commute_in": 75}, load_rules()))

    def test_input_has_no_coordinates(self):
        s = json.dumps(build_claude_input("2026-11-24", "am", [sample_slot()], load_rules()), ensure_ascii=False)
        for word in ("lat", "lon", "nx", "ny", "latitude", "longitude"):
            self.assertNotIn(f'"{word}"', s)

    def test_input_carries_rule_score_and_rubric(self):
        slot = sample_slot()
        d = build_claude_input("2026-11-24", "am", [slot], load_rules())
        self.assertEqual(d["slots"][0]["rule_score"], slot["eval"]["rule_score"])
        self.assertEqual(d["rubric"]["floors"], load_rules()["floors"])
        self.assertEqual((d["date"], d["run"]), ("2026-11-24", "am"))


if __name__ == "__main__":
    unittest.main()
