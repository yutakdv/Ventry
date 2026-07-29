"""서빙 12테이블 조립 (raw+interim → DDL 행). AI-05-2, 스펙 §3-1·§4.

raw seoul_commercial(매출·인구·점포·변화) + reb_rent(임대료 단가·전환율·공실) +
interim join(경쟁밀도·최근접역) → DDL 컬럼과 정확히 일치하는 DataFrame dict.
파생(초기비용·점수)은 preprocess.cost/score 순수 함수로 계산(부담률·종합점수 미굽).
"""
from __future__ import annotations

import json

import pandas as pd
from pyproj import Transformer

from batch.paths import INTERIM_DIR, RAW_DIR, logger
from batch.preprocess import cost, score

SEOUL_CM = RAW_DIR / "seoul_commercial"
REB = RAW_DIR / "reb_rent"
JOIN = INTERIM_DIR / "join"

CAFE_CODE = "CS100010"  # 커피-음료 = cafe
FNB_PREFIX = "CS1"  # CS1xxxxx = 외식업 (그 외 CS100010 제외분 = food)
INDUSTRIES = ("cafe", "food")
# 상권변화지표 → 성장 순위 (w4; 확장>다이나믹>정체>축소, assumptions)
GROWTH_RANK = {"LH": 3, "LL": 2, "HH": 1, "HL": 0}
# reb_rent STATBL_ID (assumptions #13) — 파일명에 포함
RENT_FILES = {"small": "rent_small_T248223134698125.json",
              "medium": "rent_medium_T244363134858603.json",
              "complex": "rent_complex_T244913134948657.json"}
CONVERT_FILES = {"small": "convert_small_T246253134905233.json",
                 "medium": "convert_medium_T241883134877452.json",
                 "complex": "convert_complex_T243133134985812.json"}
VACANCY_FILES = {"small": "vacancy_small_T241833134686576.json",
                 "medium": "vacancy_medium_T249633134845544.json"}

_T_5181_TO_WGS84 = Transformer.from_crs(5181, 4326, always_xy=True)

# 분기 실일수 — 분기 총계를 일평균으로 환산할 때 쓴다 (2026 평년 기준, CM F-2).
_QUARTER_DAYS = {"1": 90, "2": 91, "3": 92, "4": 92}


def _load(path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return data if isinstance(data, list) else data.get("data", data)


def _latest_quarter(rows: list[dict], field: str = "STDR_YYQU_CD") -> str:
    return max(r[field] for r in rows if r.get(field))


def _sales_industry(code: str) -> str | None:
    if code == CAFE_CODE:
        return "cafe"
    if code and code.startswith(FNB_PREFIX):
        return "food"
    return None


# ── 임대료 룩업 (단가 천원/㎡, 전환율 %, 공실률 %) ──────────────────────────────
def _reb_latest_by_district(files: dict[str, str],
                            value_field: str = "DTA_VAL") -> tuple[dict, str]:
    """({상권명(CLS_NM): (value, store_type)}, 최신 분기) — small→medium→complex 우선순위.

    분기를 함께 돌려주는 이유: 화면 기준일(`data_source_meta`)과 `rent.quarter` 를 원천에서
    끌어오기 위해서다. 하드코딩하면 다음 분기 재수집 때 값만 새것이고 기준일이 옛것으로 남는다.
    """
    out: dict[str, tuple[float, str]] = {}
    newest = ""
    for store_type in ("complex", "medium", "small"):  # 역순 삽입 → small이 최종 승리
        fname = files.get(store_type)
        if not fname:
            continue
        rows = [r for r in _load(REB / fname) if (r.get("CLS_FULLNM") or "").startswith("서울")]
        if not rows:
            continue
        latest = _latest_quarter(rows, "WRTTIME_IDTFR_ID")
        newest = max(newest, latest)
        for r in rows:
            if r["WRTTIME_IDTFR_ID"] == latest and r.get(value_field) is not None:
                out[r["CLS_NM"].strip()] = (float(r[value_field]), store_type)
    return out, newest


def _build_rent(area_master: pd.DataFrame) -> tuple[pd.DataFrame, str]:
    """rent 테이블 — 상권 대표점 할당 구획의 단가·전환율·공실 (assignment 승계).

    반환: (rent DataFrame, REB 최신 분기 '20261' 표기)
    """
    assign = pd.read_csv(JOIN / "rent_assignment.csv", dtype=str).fillna("")
    assign.columns = [c.lstrip("﻿") for c in assign.columns]
    unit_px, rent_quarter = _reb_latest_by_district(RENT_FILES)
    convert, _ = _reb_latest_by_district(CONVERT_FILES)
    vacancy, _ = _reb_latest_by_district(VACANCY_FILES)
    rent_quarter = _yyqq(rent_quarter)
    # region 평균(구획 미할당 '' → reb_region 평균 단가)
    def _px_of(n: str):
        return unit_px.get(n.strip(), (None, None))[0]

    tmp = assign.assign(_px=assign["reb_district_name"].map(_px_of))

    def _avg_by(key: str) -> dict[str, float]:
        """key 별 **구획 단위** 평균 단가. 같은 구획을 여러 상권이 참조하므로 중복을 뺀다."""
        out: dict[str, float] = {}
        for value, g in tmp.groupby(key):
            by_district = {
                str(n).strip(): v
                for n, v in zip(g["reb_district_name"], g["_px"], strict=True)
                if pd.notna(v) and str(n).strip()
            }
            if by_district:
                out[value] = sum(by_district.values()) / len(by_district)
        return out

    region_px = _avg_by("reb_region")
    # 자치구 평균 — assign_level 'gu_avg' 의 정의(assumptions #18 「자치구 내 구획들의 평균」)를
    # 실제로 구현한다. 이게 없으면 gu_avg 가 권역 평균으로 흘러 region_avg 와 구분되지 않고,
    # 23개 자치구가 R-ONE 권역 4종 값으로 붕괴한다 (리뷰 #8).
    gu_px = _avg_by("sigungu_name")

    def _opt(lookup: dict, name: str):  # 전환율·공실 값(round) 또는 None
        hit = lookup.get(name)
        return round(hit[0], 3) if hit else None

    rows = []
    for r in assign.itertuples():
        name = (r.reb_district_name or "").strip()
        px, store_type = unit_px.get(name, (None, None))
        fallback = str(r.fallback_flag).lower() == "true"
        if px is None:  # 폴백 — 자치구 평균 우선, 구획 없는 자치구만 권역 평균
            px = gu_px.get(r.sigungu_name) or region_px.get(r.reb_region)
            store_type, fallback = None, True
        if px is None:
            continue
        rows.append({
            "area_code": r.area_code, "quarter": rent_quarter,
            # area-level 표시 (음식점 대표면적 기준, design 2-1). 업종별 부담률 분자는
            # initial_cost.monthly_rent 다 — 이 값은 area PK 라 업종 축이 없다 (리뷰 #2).
            "monthly_rent": cost.converted_rent(px, "food"),
            "unit_price": round(px, 4),
            "convert_rate": _opt(convert, name), "vacancy_rate": _opt(vacancy, name),
            "reb_district_cd": None, "reb_district_name": name or None,
            "reb_store_type": store_type, "fallback_flag": fallback, "source_org": "REB",
        })
    rent = pd.DataFrame(rows)
    keep = rent[rent["area_code"].isin(area_master["area_code"])].reset_index(drop=True)
    return keep, rent_quarter


# ── 상권분석 기반 base 테이블 ────────────────────────────────────────────────
def _area_master() -> pd.DataFrame:
    rows = _load(SEOUL_CM / "area_TbgisTrdarRelm.json")
    out = []
    for r in rows:
        lng, lat = _T_5181_TO_WGS84.transform(float(r["XCNTS_VALUE"]), float(r["YDNTS_VALUE"]))
        out.append({
            "area_code": r["TRDAR_CD"], "name": r["TRDAR_CD_NM"],
            "area_type_code": r.get("TRDAR_SE_CD"), "area_type_name": r.get("TRDAR_SE_CD_NM"),
            "sigungu_code": r.get("SIGNGU_CD"), "sigungu_name": r.get("SIGNGU_CD_NM"),
            "adstrd_code": r.get("ADSTRD_CD"), "adstrd_name": r.get("ADSTRD_CD_NM"),
            "lat": round(lat, 7), "lng": round(lng, 7),
            "area_m2": int(float(r["RELM_AR"])) if r.get("RELM_AR") else None,
        })
    return pd.DataFrame(out).drop_duplicates("area_code").reset_index(drop=True)


def _latest_rows(fname: str) -> tuple[list[dict], str]:
    rows = _load(SEOUL_CM / fname)
    latest = _latest_quarter(rows)
    return [r for r in rows if r["STDR_YYQU_CD"] == latest], latest


def _quarter_days(quarter: str) -> int:
    """'20261' → 90. 분기 총계를 일평균으로 환산할 때 쓰는 실일수."""
    return _QUARTER_DAYS[quarter[-1]]


def _yyqq(quarter: str) -> str:
    """REB 표기 '202601' → 서울 상권분석 표기 '20261'. 이미 5자리면 그대로."""
    return f"{quarter[:4]}{int(quarter[4:])}" if len(quarter) > 5 else quarter


def _as_of(quarter: str) -> str:
    """'20261' → '2026-Q1' (화면 기준일 표기, 스펙 §0-4)."""
    return f"{quarter[:4]}-Q{quarter[-1]}"


def _sales_and_stores() -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    """매출(area×industry 월매출·건수) + 점포수(area×industry) + est_sales(점포당 월)."""
    sales_rows, sq = _latest_rows("selng_VwsmTrdarSelngQq.json")
    stor_rows, _ = _latest_rows("stor_VwsmTrdarStorQq.json")

    def agg(rows, amt_field, cnt_field):
        acc: dict[tuple, dict] = {}
        for r in rows:
            ind = _sales_industry(r.get("SVC_INDUTY_CD"))
            if ind is None:
                continue
            a = acc.setdefault((r["TRDAR_CD"], ind), {"amt": 0.0, "cnt": 0.0})
            a["amt"] += float(r.get(amt_field) or 0)
            a["cnt"] += float(r.get(cnt_field) or 0)
        return acc

    sales_acc = agg(sales_rows, "THSMON_SELNG_AMT", "THSMON_SELNG_CO")
    store_acc = agg(stor_rows, "STOR_CO", "SIMILR_INDUTY_STOR_CO")

    sales, store, est = [], [], {}
    zero_sales = []
    for (area, ind), s in sales_acc.items():
        monthly_won = s["amt"] / 3.0  # 분기합 → 월 (THSMON=요일합=분기합, assumptions #41)
        sales.append({"area_code": area, "quarter": sq, "industry": ind,
                      "industry_code": None, "monthly_sales": round(monthly_won / 10000),
                      "monthly_sales_cnt": int(s["cnt"] / 3)})
        n_store = store_acc.get((area, ind), {}).get("amt", 0)  # STOR_CO 합
        if not n_store or n_store <= 0:
            continue
        per_store = round(monthly_won / n_store / 10000)  # 점포당 월매출 만원
        if per_store <= 0:
            # 점포는 있는데 매출이 0으로 반올림된다 = 원천 미집계다. "매출 0인 상권"이 아니다.
            # 그대로 적재하면 BE 부담률이 rent/0 = Infinity 가 되어 계약(number)을 깬다 (BE D-04).
            # 결측을 0으로 강등하지 않는다 — 점수 대상에서 제외한다 (리뷰 #21).
            zero_sales.append((area, ind))
            continue
        est[(area, ind)] = per_store
    if zero_sales:
        logger.warning("추정매출 결측(0) %d개 (상권,업종) — location_score 제외: %s",
                       len(zero_sales), zero_sales[:5])
    for (area, ind), st in store_acc.items():
        store.append({"area_code": area, "quarter": sq, "industry": ind,
                      "store_cnt": int(st["amt"]), "similar_store_cnt": int(st["cnt"])})
    return pd.DataFrame(sales), pd.DataFrame(store), est


def _pop_table(fname: str, tot_field: str, col: str, extra: dict | None = None) -> pd.DataFrame:
    rows, q = _latest_rows(fname)
    out = []
    for r in rows:
        row = {"area_code": r["TRDAR_CD"], "quarter": q, col: int(float(r.get(tot_field) or 0))}
        for dst, src in (extra or {}).items():
            row[dst] = int(float(r.get(src) or 0)) if r.get(src) is not None else None
        out.append(row)
    return pd.DataFrame(out).drop_duplicates(["area_code", "quarter"]).reset_index(drop=True)


def _change_index() -> pd.DataFrame:
    rows, q = _latest_rows("ix_VwsmTrdarIxQq.json")
    out = []
    for r in rows:
        out.append({
            "area_code": r["TRDAR_CD"], "quarter": q,
            "change_code": r.get("TRDAR_CHNGE_IX"), "change_name": r.get("TRDAR_CHNGE_IX_NM"),
            "oper_avg_months": r.get("OPR_SALE_MT_AVRG"),
            "close_avg_months": r.get("CLS_SALE_MT_AVRG"),
        })
    return pd.DataFrame(out).drop_duplicates(["area_code", "quarter"]).reset_index(drop=True)


def _store_density(store: pd.DataFrame, area_master: pd.DataFrame,
                   quarter: str) -> pd.DataFrame:
    """상권분석 점포 + interim 경쟁밀도(permit) 병합."""
    perm = pd.read_csv(JOIN / "store_density.csv", dtype=str)
    perm.columns = [c.lstrip("﻿") for c in perm.columns]
    perm = perm[["area_code", "industry", "permit_store_cnt", "store_per_10k_m2"]]
    df = store.merge(perm, on=["area_code", "industry"], how="outer")
    df["permit_store_cnt"] = pd.to_numeric(df["permit_store_cnt"], errors="coerce")
    df["store_per_10k_m2"] = pd.to_numeric(df["store_per_10k_m2"], errors="coerce")
    df["quarter"] = df["quarter"].fillna(quarter)
    return df[df["area_code"].isin(area_master["area_code"])].reset_index(drop=True)


def _transit(area_master: pd.DataFrame) -> pd.DataFrame:
    t = pd.read_csv(JOIN / "transit_assignment.csv", dtype=str)
    t.columns = [c.lstrip("﻿") for c in t.columns]
    cols = ["area_code", "nearest_station", "line", "distance_m", "daily_riders", "fallback_flag"]
    out = t[cols].copy()
    out["distance_m"] = pd.to_numeric(out["distance_m"], errors="coerce")
    out["daily_riders"] = pd.to_numeric(out["daily_riders"], errors="coerce")
    out["fallback_flag"] = out["fallback_flag"].astype(str).str.lower() == "true"
    return out[out["area_code"].isin(area_master["area_code"])].reset_index(drop=True)


def _data_source_meta(sales_quarter: str, rent_quarter: str) -> pd.DataFrame:
    """화면 기준일의 단일 원천. 분기 값은 **원천 최신 분기에서 끌어온다** — 하드코딩하면
    다음 분기 재수집 때 값만 새것이고 화면 기준일이 옛것으로 남는다 (스펙 §0-4)."""
    rows = [
        ("sales", _as_of(sales_quarter), "서울 상권분석 추정매출 (분기)",
         "당월매출=분기합/3 환산", "2026-07-21"),
        # 면적은 상수에서 끌어온다 — 하드코딩하면 대표면적이 갱신될 때 화면 라벨만 옛 숫자로
        # 남는다. 실제로 그렇게 어긋난 채 적재된 전례가 있다 (이슈 #152·#151).
        ("rent", _as_of(rent_quarter), "한국부동산원 ○○상권 분기 평균 (추정)",
         f"환산임대료 음식점 {cost.REPRESENTATIVE_AREA_M2['food']:g}㎡ 기준", "2026-07-21"),
        # 유동인구도 기준일·출처 라벨을 갖는다 — 이 행이 없어 이 지표만 라벨 없이 나갔다
        # (불변 원칙 4, BE D-22 잔여 몫).
        ("floating", _as_of(sales_quarter), "서울 열린데이터광장 상권 분기 집계 (일평균 환산)",
         "분기 총계 ÷ 분기 실일수", "2026-07-21"),
        ("premium", "2025년(전년 기준)", "한국부동산원 권리금 연간 조사(전년 기준)",
         "서울 숙박·음식점업", "2026-07-21"),
        ("transit", "2026-07", "서울 지하철 승하차 (일평균)",
         "환승역 정규명 합산", "2026-07-21"),
        ("interior", "2025", "공정위 가맹정보 2025 (가맹점 기준·상향, 만원)",
         "인테리어·시설 프록시", "2026-07-24"),
        # BE ScenarioBuilder 가 상품 카드 기준일로 이 행을 조회한다 — 없으면 시나리오 화면이
        # 500 이 된다(실데이터 결선에서 실측, 이슈 #87). 값은 검수본의 공고일 기준.
        ("finance_product", "2026-07-21", "정책자금·보증·대출 상품 (공고 기준)",
         "전건 사람 검수본", "2026-07-25"),
    ]
    return pd.DataFrame(rows, columns=["source", "as_of", "label", "note", "collected_on"])


# ── 파생: 초기비용 + 점수 ────────────────────────────────────────────────────
def _derive(
    rent, est, floating, resident, worker, density, change, quarter
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """초기비용(area×industry) + 점수 metrics(교통 유입 제외)."""
    seoul_median_unit_price = float(rent["unit_price"].median())
    rent_df = pd.concat(
        [rent[["area_code", "unit_price"]].assign(industry=ind) for ind in INDUSTRIES],
        ignore_index=True)
    initial_cost = cost.build_initial_cost(rent_df, seoul_median_unit_price)

    rent_by_area = rent.set_index("area_code")["monthly_rent"].to_dict()
    flo = floating.set_index("area_code")["daily_floating"].to_dict()
    res = resident.set_index("area_code")["resident_pop"].to_dict()
    wrk = worker.set_index("area_code")["worker_pop"].to_dict()
    growth = change.set_index("area_code")["change_code"].map(GROWTH_RANK).to_dict()
    # w3(경쟁여유)의 입력은 **면적 정규화 밀도**다 (스펙 §4-3 · competition.py 독스트링 ·
    # assumptions #41 ⑥ · §12 검증 피처가 모두 이 정의). 원시 개수를 쓰면 넓은 상권이 구조적으로
    # 불리해져 축이 면적 대리변수로 오염된다 (리뷰 #1 안 A, 2026-07-27 3인 합의).
    # .dropna() 는 미조인 NaN 을 키 부재로 강등한다 — `or 0` 은 NaN 을 통과시켜(bool(nan) is True)
    # 미조인 상권의 백분위를 0.0(경쟁여유 최하위)으로 반전시켰다 (리뷰 #2).
    den = (density.set_index(["area_code", "industry"])["store_per_10k_m2"]
           .dropna().to_dict())
    metrics = []
    no_floating = []
    for (area, ind), es in est.items():
        mr = rent_by_area.get(area)
        if mr is None:
            continue
        pedestrian = flo.get(area)
        if not pedestrian:
            # 원천 유동인구에 없는 상권. 0으로 채우면 w1 최하위 + 화면 범위가 "0 ~"로 시작한다
            # (CM F-4). 매출 결측과 같은 규칙으로 점수 대상에서 제외한다 (리뷰 #20).
            no_floating.append((area, ind))
            continue
        metrics.append({
            "area_code": area, "industry": ind,
            "pedestrian": pedestrian, "backing": res.get(area, 0) + wrk.get(area, 0),
            "est_sales": es, "monthly_rent": mr,
            # 키 부재 = 인허가에서 확인된 업소 0건 → 밀도 0 (경쟁여유 최상위)
            #
            # growth_rank 결측 기본값 1(정체)은 **다른 결측과 규칙이 다르다** — 매출·유동인구는
            # 결측이면 점수 대상에서 제외하는데(리뷰 #20·#21) 성장만 중간값을 대입한다.
            # 성장은 5단계 순위형이라 「제외」가 곧 상권 하나를 통째로 후보에서 지우는 것이 되고,
            # 하향 안전 방향(정체=중립)으로 채우는 편이 손실이 작기 때문이다. 규칙이 다르다는
            # 사실 자체를 등재해 둔다 (assumptions #100 · #94, AI 리뷰 P2).
            "density": den.get((area, ind), 0.0), "growth_rank": growth.get(area, 1),
            "daily_floating": pedestrian, "quarter": quarter,
        })
    missing_den = sum(1 for (area, ind) in est if (area, ind) not in den)
    if missing_den:
        logger.warning("인허가 미조인 %d개 (상권,업종) — 경쟁밀도 0 처리", missing_den)
    if no_floating:
        logger.warning("유동인구 결측 %d개 (상권,업종) — location_score 제외: %s",
                       len(no_floating), no_floating[:5])
    return initial_cost, pd.DataFrame(metrics)


def assemble() -> dict[str, pd.DataFrame]:
    logger.info("serving: base 테이블 조립 시작")
    area = _area_master()
    sales, store, est = _sales_and_stores()
    floating = _pop_table("flpop_VwsmTrdarFlpopQq.json", "TOT_FLPOP_CO", "daily_floating")
    # TOT_FLPOP_CO 는 분기 총계다(요일·시간대 필드 합 = TOT 로 검증, CM F-2). 계약의
    # daily_floating 은 '일평균'이므로 분기 실일수로 나눈다 — 계약을 바꾸지 않고 데이터를 맞춘다.
    # 상주·직장 인구는 시점 재고량이라 환산 대상이 아니다.
    # 전 행에 같은 상수를 나누므로 **백분위(w1)는 불변**이며 표시값만 바뀐다.
    _days = _quarter_days(floating["quarter"].iloc[0])
    floating["daily_floating"] = (floating["daily_floating"] / _days).round().astype(int)
    resident = _pop_table("repop_VwsmTrdarRepopQq.json", "TOT_REPOP_CO", "resident_pop",
                          {"household_cnt": "TOT_HSHLD_CO"})
    worker = _pop_table("wrcpop_VwsmTrdarWrcPopltnQq.json", "TOT_WRC_POPLTN_CO", "worker_pop")
    change = _change_index()
    rent, rent_quarter = _build_rent(area)
    quarter = str(sales["quarter"].iloc[0])   # 서울 상권분석 최신 분기 — 하드코딩 금지
    density = _store_density(store, area, quarter)
    transit = _transit(area)

    initial_cost, metrics = _derive(rent, est, floating, resident, worker, density, change,
                                    quarter)
    # 교통 유입 주입: 역 미매칭(폴백) 상권은 유입 0 (거리 ∞) 처리 (스펙 §3-1)
    tr = transit[["area_code", "daily_riders", "distance_m"]].rename(
        columns={"daily_riders": "riders"})
    metrics = metrics.merge(tr, on="area_code", how="left")
    metrics["riders"] = metrics["riders"].fillna(0)
    metrics["distance_m"] = metrics["distance_m"].fillna(10**9)
    location_score = score.build_location_score(metrics)

    # initial_cost: monthly_rent 는 업종별 부담률 분자로 유지한다 (리뷰 #2).
    # rent.monthly_rent 는 상권 단위 표기값(음식점 대표면적 기준)이라 업종 부담률에 못 쓴다.
    initial_cost = initial_cost.assign(based_on_quarter=quarter)

    tables = {
        "data_source_meta": _data_source_meta(quarter, rent_quarter),
        "commercial_area": area,
        "sales": sales, "floating_pop": floating, "resident_pop": resident,
        "worker_pop": worker, "store_density": density, "change_index": change,
        "rent": rent, "transit": transit,
        "location_score": location_score, "initial_cost": initial_cost,
    }
    # FK 무결성: 상권 마스터에 없는 area_code 행 제거 (data_source_meta 는 area 무관)
    valid = set(area["area_code"])
    for name, df in tables.items():
        if name != "data_source_meta" and "area_code" in df.columns:
            tables[name] = df[df["area_code"].isin(valid)].reset_index(drop=True)
        logger.info("  %-18s %5d행", name, len(tables[name]))
    return tables
