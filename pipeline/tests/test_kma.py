import json
import unittest
from datetime import datetime
from pathlib import Path

from bri.kma import build_url, latlon_to_grid, parse_items, parse_pcp, parse_sno, pick_base

FIX = Path(__file__).parent / "fixtures"


class TestKma(unittest.TestCase):
    def test_grid(self):
        self.assertEqual(latlon_to_grid(37.5665, 126.978), (60, 127))
        self.assertEqual(latlon_to_grid(35.1796, 129.0756), (98, 76))

    def test_pick_base(self):
        self.assertEqual(pick_base(datetime(2026, 11, 24, 6, 30)), ("20261124", "0500"))
        self.assertEqual(pick_base(datetime(2026, 11, 24, 17, 0)), ("20261124", "1400"))
        self.assertEqual(pick_base(datetime(2026, 11, 24, 17, 12)), ("20261124", "1700"))
        self.assertEqual(pick_base(datetime(2026, 11, 24, 1, 0)), ("20261123", "2300"))

    def test_parse_strings(self):
        for s, v in [("강수없음", 0.0), ("1mm 미만", 0.5), ("2.5mm", 2.5), ("30.0~50.0mm", 40.0), ("50.0mm 이상", 50.0)]:
            self.assertEqual(parse_pcp(s), v)
        for s, v in [("적설없음", 0.0), ("1cm 미만", 0.5), ("1.2cm", 1.2), ("5.0cm 이상", 5.0)]:
            self.assertEqual(parse_sno(s), v)

    def test_parse_items(self):
        s = parse_items((FIX / "kma_vilage_sample.json").read_text(encoding="utf-8"), "kma_vilage")
        self.assertEqual(s.model, "kma_vilage")
        self.assertEqual(s.times, [datetime(2026, 11, 24, 8), datetime(2026, 11, 24, 9)])
        self.assertEqual(s.values["temp"], [-1.0, 0.0])
        self.assertEqual(s.values["precip"], [0.5, 0.0])
        self.assertEqual(s.values["snowfall"], [0.0, 0.5])
        self.assertEqual(s.values["pty"], [0.0, 3.0])
        self.assertEqual(s.values["pop"], [60.0, 70.0])
        self.assertEqual(s.values["wind"], [2.1, 3.4])
        for var in ("dewpoint", "apparent", "surface_temp", "gust"):
            self.assertTrue(all(v is None for v in s.values[var]), var)

    def test_xml_error_body_returns_none(self):
        self.assertIsNone(parse_items((FIX / "kma_error.xml").read_text(encoding="utf-8"), "kma_vilage"))

    def test_result_code_not_00_returns_none(self):
        body = {"response": {"header": {"resultCode": "03", "resultMsg": "NO_DATA"}}}
        self.assertIsNone(parse_items(json.dumps(body), "kma_vilage"))

    def test_url_encodes_key(self):
        url = build_url("a+b/c=", "20261124", "0500", 60, 127)
        self.assertIn("serviceKey=a%2Bb%2Fc%3D", url)
        for part in ("dataType=JSON", "numOfRows=1000", "base_date=20261124", "base_time=0500", "nx=60", "ny=127"):
            self.assertIn(part, url)


if __name__ == "__main__":
    unittest.main()
