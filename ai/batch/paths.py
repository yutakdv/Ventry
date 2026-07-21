"""배치 공통 경로·로거 (collect·preprocess·load 공용)."""
from __future__ import annotations

import logging
from pathlib import Path

# ai/batch/paths.py → ai/
AI_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = AI_ROOT.parent
RAW_DIR = AI_ROOT / "data" / "raw"
INTERIM_DIR = AI_ROOT / "data" / "interim"

logger = logging.getLogger("batch")


def setup_logging(level: int = logging.INFO) -> None:
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
