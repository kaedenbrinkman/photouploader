"""Build lastError text for DynamoDB (message + redacted log tail)."""

from __future__ import annotations

import re
from pathlib import Path

_REDACT_PATTERNS = [
    (re.compile(r"(?i)(authorization:\s*)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)(bearer\s+)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)(token[=:]\s*)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)(secret[=:]\s*)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)(password[=:]\s*)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)(AWS_SECRET_ACCESS_KEY[=:]\s*)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)(AWS_SESSION_TOKEN[=:]\s*)\S+"), r"\1[REDACTED]"),
]


def redact_log_line(line: str) -> str:
    for pat, repl in _REDACT_PATTERNS:
        line = pat.sub(repl, line)
    return line


def format_last_error(message: str, log_path: Path | None, *, tail_lines: int = 50, max_chars: int = 3500) -> str:
    msg = message.strip() or "worker failed"
    if log_path and log_path.is_file():
        try:
            lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
            tail = [redact_log_line(ln) for ln in lines[-tail_lines:]]
            if tail:
                msg = msg + f"\n\n--- worker log (last {len(tail)} lines) ---\n" + "\n".join(tail)
        except OSError:
            pass
    if len(msg) > max_chars:
        msg = msg[-max_chars:]
    return msg
