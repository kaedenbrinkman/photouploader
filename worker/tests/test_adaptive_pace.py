import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from gphotos.adaptive_pace import AdaptivePacer, parse_retry_after_seconds


def test_parse_retry_after_seconds():
    assert parse_retry_after_seconds("12") == 12.0
    assert parse_retry_after_seconds(None) is None


def test_adaptive_pacer_increases_on_rate_limit():
    p = AdaptivePacer(start_interval_s=1.0, min_interval_s=0.5, max_interval_s=30.0)
    before = p.interval_s
    sleep = p.on_rate_limited(None)
    assert sleep >= before
    assert p.interval_s >= before
