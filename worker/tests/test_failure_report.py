from pathlib import Path

from worker.failure_report import format_last_error, redact_log_line


def test_redact_log_line_masks_bearer():
    assert "[REDACTED]" in redact_log_line("Authorization: Bearer secret-token-here")


def test_format_last_error_includes_tail(tmp_path: Path):
    log = tmp_path / "worker.log"
    log.write_text("line1\nBearer abc123\nline3\n", encoding="utf-8")
    msg = format_last_error("boom", log, tail_lines=10)
    assert "boom" in msg
    assert "worker log" in msg
    assert "abc123" not in msg
    assert "[REDACTED]" in msg
