import json
import os
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from zoneinfo import ZoneInfo

from bri.__main__ import main
from bri.openmeteo import OpenMeteoError, parse_response
from bri.rules import load_rules

FIX = Path(__file__).parent / "fixtures"
NOW = datetime(2026, 11, 24, 6, 30, tzinfo=ZoneInfo("Asia/Seoul"))  # 화요일, 고정 응답 범위 안
ENV = {"HOME_LAT": "37.5665", "HOME_LON": "126.978", "WORK_LAT": "37.5665", "WORK_LON": "126.978",
       "KMA_SERVICE_KEY": "dummy"}


def om_series(*_a, **_k):
    body = json.loads((FIX / "openmeteo_seoul.json").read_text(encoding="utf-8"))
    return parse_response(body, load_rules()["openmeteo_models"])


class TestCli(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.work, self.docs = root / "work", root / "docs"
        self.out = root / "gh_output"
        self.env = patch.dict(os.environ, dict(ENV, GITHUB_OUTPUT=str(self.out)))
        self.env.start()
        self.clock = patch("bri.publish.kst_now", return_value=NOW)
        self.clock.start()

    def tearDown(self):
        self.clock.stop()
        self.env.stop()
        self.tmp.cleanup()

    def prepare(self, *extra):
        return main(["prepare", "--work", str(self.work), "--docs", str(self.docs), *extra])

    def finalize(self):
        return main(["finalize", "--work", str(self.work), "--docs", str(self.docs)])

    def prepare_ok(self):
        with patch("bri.openmeteo.fetch_point", side_effect=om_series), \
                patch("bri.kma.fetch_point", return_value=None):
            return self.prepare("--run", "am")

    def test_prepare_fails_without_touching_docs(self):
        with patch("bri.openmeteo.fetch_point", side_effect=OpenMeteoError("timeout")):
            rc = self.prepare("--run", "am")
        self.assertNotEqual(rc, 0)
        self.assertFalse((self.docs / "latest.json").exists())

    def test_prepare_writes_input_without_coordinates(self):
        self.assertEqual(self.prepare_ok(), 0)
        text = (self.work / "claude_input.json").read_text(encoding="utf-8")
        self.assertNotIn("37.5665", text)
        self.assertNotIn("126.978", text)
        self.assertEqual([s["id"] for s in json.loads(text)["slots"]], ["commute_in", "commute_out"])
        self.assertIn("skip=false", self.out.read_text())

    def test_finalize_falls_back_to_rule(self):
        self.prepare_ok()
        (self.work / "claude_output.json").write_text('{"slots": "broken"}', encoding="utf-8")
        self.assertEqual(self.finalize(), 0)
        latest = json.loads((self.docs / "latest.json").read_text(encoding="utf-8"))
        self.assertEqual(latest["source"], "rule")
        for s in latest["slots"]:
            self.assertTrue(s["score"] == s["rule_score"] or s["floor_applied"] is not None)
        self.assertTrue((self.docs / "history" / "2026-11-24-am.json").exists())
        self.assertTrue((self.docs / "rules.json").exists())

    def test_finalize_uses_valid_claude_output(self):
        self.prepare_ok()
        state = json.loads((self.work / "state.json").read_text(encoding="utf-8"))
        slots = [{"id": s["id"], "score": s["eval"]["rule_score"], "confidence": "높음", "summary": "테스트",
                  "reasons": ["이유"], "deviation_reason": None} for s in state["slots"]]
        (self.work / "claude_output.json").write_text(json.dumps({"slots": slots}), encoding="utf-8")
        self.finalize()
        latest = json.loads((self.docs / "latest.json").read_text(encoding="utf-8"))
        self.assertEqual(latest["source"], "claude")
        self.assertEqual(latest["slots"][0]["summary"], "테스트")

    def test_kma_failure_drops_source(self):
        self.prepare_ok()
        self.finalize()
        latest = json.loads((self.docs / "latest.json").read_text(encoding="utf-8"))
        self.assertNotIn("kma_vilage", latest["models_used"])
        self.assertEqual(len(latest["models_used"]), 4)

    def test_prepare_fails_when_too_few_models(self):
        # I1: 쓸 수 있는 모델이 최소치보다 적으면 100점을 게시하지 않고 실패한다
        one_model = lambda *_a, **_k: om_series()[:1]  # noqa: E731
        with patch("bri.openmeteo.fetch_point", side_effect=one_model), \
                patch("bri.kma.fetch_point", return_value=None):
            rc = self.prepare("--run", "am")
        self.assertNotEqual(rc, 0)
        self.assertFalse((self.work / "claude_input.json").exists())
        self.assertFalse((self.docs / "latest.json").exists())

    def test_second_prepare_skips(self):
        self.prepare_ok()
        self.finalize()
        self.assertEqual(self.prepare_ok(), 0)
        self.assertTrue(self.out.read_text().strip().endswith("skip=true"))


if __name__ == "__main__":
    unittest.main()
