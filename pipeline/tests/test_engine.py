import json
import unittest
from datetime import datetime

from bri.engine import apply_floor, build_model_windows, evaluate_slot, grade, ice_risk, point_features
from bri.rules import REPO_ROOT, load_rules
from bri.series import ModelSeries

VEC = REPO_ROOT / "rules" / "test_vectors"


class TestEngine(unittest.TestCase):
    def test_vectors(self):
        rules = load_rules()
        paths = sorted(VEC.glob("*.json"))
        self.assertEqual(len(paths), 4)
        for p in paths:
            with self.subTest(p.name):
                v = json.loads(p.read_text(encoding="utf-8"))
                r = evaluate_slot(v["points"], rules)
                e = v["expected"]
                self.assertEqual((r["ice_risk"], r["rule_score"], r["confidence"], r["floor_cap"], r["floor_label"]),
                                 (e["ice_risk"], e["rule_score"], e["confidence"], e["floor_cap"], e["floor_label"]))
                final, _ = apply_floor(r["rule_score"], r["floor_cap"], r["floor_label"])
                self.assertEqual((final, grade(final, rules)), (e["final_score"], e["grade"]))

    def test_missing_variables(self):
        w = {"temp": [None, None], "apparent": [None, None], "dewpoint": [None, None], "precip": [None, None],
             "snowfall": [None, None], "gust": [None, None], "surface_temp": [None, None], "pty": [None, None],
             "past24_precip": None, "past48_snowfall": None}
        ok = dict(w, temp=[3.0, 2.0], precip=[0.0, 0.0], snowfall=[0.0, 0.0])
        pf = point_features({"jma": ok, "dead": w}, load_rules())
        self.assertEqual(pf["n_models"], 1)
        self.assertIsNone(pf["gust_ms"])
        self.assertIsNone(pf["surface_c"])
        self.assertEqual(ice_risk(pf, load_rules()), "없음")

    def test_apply_floor(self):
        self.assertEqual(apply_floor(72, 59, "ice"), (59, "ice"))
        self.assertEqual(apply_floor(30, 39, "snow"), (30, None))
        self.assertEqual(apply_floor(72, None, None), (72, None))

    def test_grade_bounds(self):
        rules = load_rules()
        self.assertEqual([grade(s, rules) for s in (100, 80, 79, 60, 59, 40, 39, 0)],
                         ["좋음", "좋음", "주의", "주의", "비추천", "비추천", "타지 않음", "타지 않음"])

    def test_build_model_windows(self):
        times = [datetime(2026, 11, 22, 8) + __import__("datetime").timedelta(hours=h) for h in range(50)]
        s = ModelSeries("m", times, {"temp": [1.0] * 50, "snowfall": [0.1] * 50, "precip": [0.2] * 50})
        start, end = datetime(2026, 11, 24, 8), datetime(2026, 11, 24, 9)
        w = build_model_windows([s], start, end)["m"]
        self.assertEqual(w["temp"], [1.0, 1.0])
        self.assertEqual(w["gust"], [None, None])  # 없는 변수는 None으로 채운다
        self.assertAlmostEqual(w["past48_snowfall"], 4.8)
        self.assertAlmostEqual(w["past24_precip"], 4.8)

    def test_past_values_none_when_series_does_not_cover_window(self):
        # 기상청 단기예보처럼 발표 이후 값만 있는 시계열(C1): 과거 합을 0으로 만들면 안 된다
        from datetime import timedelta
        times = [datetime(2026, 11, 24, 6) + timedelta(hours=h) for h in range(5)]
        s = ModelSeries("kma_vilage", times, {"temp": [-1.0] * 5, "precip": [0.0] * 5, "snowfall": [0.0] * 5})
        w = build_model_windows([s], datetime(2026, 11, 24, 8), datetime(2026, 11, 24, 9))["kma_vilage"]
        self.assertIsNone(w["past24_precip"])
        self.assertIsNone(w["past48_snowfall"])

    def test_kma_window_does_not_erase_ice_high(self):
        # Open-Meteo 4개 중 2개가 과거 적설을 보고하면, 기상청 창이 더해져도 결빙 높음이 유지된다
        from datetime import timedelta
        rules = load_rules()
        om = {}
        for name, snow in (("a", 0.5), ("b", 0.3), ("c", 0.0), ("d", 0.0)):
            om[name] = {"temp": [-1.0, -1.0], "apparent": [-1.0, -1.0], "dewpoint": [-5.0, -5.0],
                        "precip": [0.0, 0.0], "snowfall": [0.0, 0.0], "gust": [3.0, 3.0],
                        "surface_temp": [None, None], "pty": [None, None],
                        "past24_precip": 0.0, "past48_snowfall": snow}
        times = [datetime(2026, 11, 24, 6) + timedelta(hours=h) for h in range(5)]
        kma = ModelSeries("kma_vilage", times, {"temp": [-1.0] * 5, "precip": [0.0] * 5, "snowfall": [0.0] * 5,
                                                 "pty": [0.0] * 5})
        om["kma_vilage"] = build_model_windows([kma], datetime(2026, 11, 24, 8), datetime(2026, 11, 24, 9))["kma_vilage"]
        r = evaluate_slot({"home": om, "work": om}, rules)
        self.assertEqual(r["ice_risk"], "높음")
        self.assertEqual(r["floor_cap"], 39)


if __name__ == "__main__":
    unittest.main()
