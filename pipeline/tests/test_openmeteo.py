import json
import unittest
from datetime import datetime
from pathlib import Path

from bri.openmeteo import build_url, parse_response
from bri.series import ModelSeries, past_sum, window

FIX = Path(__file__).parent / "fixtures"


class TestOpenMeteo(unittest.TestCase):
    def test_parse_fixture(self):
        body = json.load(open(FIX / "openmeteo_seoul.json", encoding="utf-8"))
        out = parse_response(body, ["ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless"])
        self.assertEqual([s.model for s in out], ["ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless"])
        self.assertEqual(len(out[0].times), 96)
        self.assertTrue(all(v is None for v in out[3].values["gust"]))  # JMA 돌풍 없음
        self.assertTrue(all(v is None for v in out[1].values["surface_temp"]))  # GFS 지표면 없음

    def test_drops_all_null_model(self):
        body = {"hourly": {"time": ["2026-11-24T08:00"], "temperature_2m_kma_seamless": [None],
                           "temperature_2m_icon_seamless": [3.0]}}
        self.assertEqual([s.model for s in parse_response(body, ["kma_seamless", "icon_seamless"])], ["icon_seamless"])

    def test_window_and_past_sum(self):
        s = ModelSeries("m", [datetime(2026, 11, 24, h) for h in range(6)], {"precip": [1, None, 2, 0, 0, 5]})
        self.assertEqual(window(s, datetime(2026, 11, 24, 2), datetime(2026, 11, 24, 3))["precip"], [2, 0])
        self.assertEqual(past_sum(s, "precip", datetime(2026, 11, 24, 3), 3), 3.0)
        self.assertIsNone(past_sum(s, "precip", datetime(2026, 11, 24, 0), 3))

    def test_url_has_ms_unit(self):
        self.assertIn("wind_speed_unit=ms", build_url(37.5, 127.0, ["icon_seamless"]))

    def test_url_params(self):
        url = build_url(37.5, 127.0, ["ecmwf_ifs025", "gfs_seamless"])
        for part in ("models=ecmwf_ifs025%2Cgfs_seamless", "past_days=2", "forecast_days=2",
                     "timezone=Asia%2FSeoul", "soil_temperature_0cm", "wind_gusts_10m"):
            self.assertIn(part, url)


if __name__ == "__main__":
    unittest.main()
