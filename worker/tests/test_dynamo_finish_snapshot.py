from worker.dynamo_store import compute_finish_snapshot


def test_compute_finish_snapshot_t3a_spot():
    snap = compute_finish_snapshot(
        {"workerStartedAt": 1_000, "instanceType": "t3a.medium", "spot": True},
        901_000,
    )
    assert snap["actualComputeSeconds"] == 900
    assert snap["actualComputeUsd"] > 0
    assert snap["finalInstanceType"] == "t3a.medium"
