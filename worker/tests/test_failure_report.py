from worker.failure_report import human_last_error


def test_human_last_error_first_line_only():
    assert human_last_error("short\ntraceback line") == "short"
