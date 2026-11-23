"""rules/rules.json(기준값 단일 원본)을 읽는다."""
import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RULES_PATH = REPO_ROOT / "rules" / "rules.json"


def load_rules(path: str | Path = DEFAULT_RULES_PATH) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)
