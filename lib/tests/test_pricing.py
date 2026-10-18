from photopipeline.pricing import estimate_shoot, load_pricing_table, actual_compute_cost


def test_estimate_scales_with_images():
    p = load_pricing_table()
    small = estimate_shoot(10, 1_000_000_000, p)
    large = estimate_shoot(500, 30_000_000_000, p)
    assert large.compute_usd > small.compute_usd
    assert large.processing_minutes > small.processing_minutes
    assert large.s3_standard_month_usd > small.s3_standard_month_usd


def test_actual_compute_cost():
    p = load_pricing_table()
    c = actual_compute_cost(600, "c7i.2xlarge", spot=True, pricing=p)
    assert c > 0
