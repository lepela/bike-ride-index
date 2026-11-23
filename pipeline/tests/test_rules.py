import unittest

from bri.rules import load_rules


class TestRules(unittest.TestCase):
    def test_loads_default(self):
        r = load_rules()
        self.assertEqual(r["floors"]["ice_high"], 39)
        self.assertEqual(r["slots"]["commute_out"]["time"], "18:30")
        self.assertEqual(len(r["openmeteo_models"]), 4)


if __name__ == "__main__":
    unittest.main()
