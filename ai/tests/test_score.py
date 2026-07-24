"""점수화 w1~w5 순수 함수 단위 테스트 (스펙 §4-3, AI-05). BE ScoreLookup 정의와 동일."""
import math

from batch.preprocess import score


def test_percentile_fraction_at_or_below():
    pop = [10.0, 20.0, 30.0, 40.0]
    assert score.percentile(30.0, pop) == 0.75  # 3/4 이하
    assert score.percentile(5.0, pop) == 0.0
    assert score.percentile(40.0, pop) == 1.0


def test_percentile_empty_population():
    assert score.percentile(1.0, []) == 0.0


def test_transit_influx_decay_500():
    assert score.transit_influx(10000, 500) == 10000 * math.exp(-1.0)
    assert score.transit_influx(10000, 0) == 10000


def test_demand_w1_equal_mean():
    assert score.demand_w1(0.6, 0.9, 0.3) == (0.6 + 0.9 + 0.3) / 3


def test_axis_values_in_unit_range():
    pop = [1.0, 2.0, 3.0]
    for v in pop:
        assert 0.0 <= score.percentile(v, pop) <= 1.0
