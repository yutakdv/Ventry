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
def _reb_latest_by_district(files: dict[str, str], value_field: str = "DTA_VAL") -> dict:
    """{상권명(CLS_NM): (value, store_type)} — small→medium→complex 우선순위."""
    out: dict[str, tuple[float, str]] = {}
    for store_type in ("complex", "medium", "small"):  # 역순 삽입 → small이 최종 승리
        fname = files.get(store_type)
        if not fname:
            continue
        rows = [r for r in _load(REB / fname) if (r.get("CLS_FULLNM") or "").startswith("서울")]
        if not rows:
            continue
        latest = _latest_quarter(rows, "WRTTIME_IDTFR_ID")
        for r in rows:
            if r["WRTTIME_IDTFR_ID"] == latest and r.get(value_field) is not None:
                out[r["CLS_NM"].strip()] = (float(r[value_field]), store_type)
    return out


def _build_rent(area_master: pd.DataFrame) -> pd.DataFrame:
    """rent 테이블 — 상권 대표점 할당 구획의 단가·전환율·공실 (assignment 승계)."""
    assign = pd.read_csv(JOIN / "rent_assignment.csv", dtype=str).fillna("")
    assign.columns = [c.lstrip("﻿") for c in assign.columns]
    unit_px = _reb_latest_by_district(RENT_FILES)
    convert = _reb_latest_by_district(CONVERT_FILES)
    vacancy = _reb_latest_by_district(VACANCY_FILES)
    # region 평균(구획 미할당 '' → reb_region 평균 단가)
    def _px_of(n: str):
        return unit_px.get(n.strip(), (None, None))[0]

    region_px: dict[str, float] = {}
    tmp = assign.assign(_px=assign["reb_district_name"].map(_px_of))
    for region, g in tmp.groupby("reb_region"):
        vals = [v for v in g["_px"] if pd.notna(v)]
        if vals:
            region_px[region] = sum(vals) / len(vals)

    def _opt(lookup: dict, name: str):  # 전환율·공실 값(round) 또는 None
        hit = lookup.get(name)
        return round(hit[0], 3) if hit else None

    rows = []
    for r in assign.itertuples():
        name = (r.reb_district_name or "").strip()
        px, store_type = unit_px.get(name, (None, None))
        fallback = str(r.fallback_flag).lower() == "true"
        if px is None:  # region_avg 폴백
            px, store_type, fallback = region_px.get(r.reb_region), None, True
        if px is None:
            continue
        rows.append({
            "area_code": r.area_code, "quarter": "20261",
            # area-level 표시·부담률 (음식점 55㎡ 기준, design 2-1)
            "monthly_rent": cost.converted_rent(px, "food"),
            "unit_price": round(px, 4),
            "convert_rate": _opt(convert, name), "vacancy_rate": _opt(vacancy, name),
            "reb_district_cd": None, "reb_district_name": name or None,
            "reb_store_type": store_type, "fallback_flag": fallback, "source_org": "REB",
        })
    rent = pd.DataFrame(rows)
    return rent[rent["area_code"].isin(area_master["area_code"])].reset_index(drop=True)


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
    for (area, ind), s in sales_acc.items():
        monthly_won = s["amt"] / 3.0  # 분기합 → 월 (THSMON=요일합=분기합, assumptions #24)
        sales.append({"area_code": area, "quarter": sq, "industry": ind,
                      "industry_code": None, "monthly_sales": round(monthly_won / 10000),
                      "monthly_sales_cnt": int(s["cnt"] / 3)})
        n_store = store_acc.get((area, ind), {}).get("amt", 0)  # STOR_CO 합
        if n_store and n_store > 0:
            est[(area, ind)] = round(monthly_won / n_store / 10000)  # 점포당 월매출 만원
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


def _store_density(store: pd.DataFrame, area_master: pd.DataFrame) -> pd.DataFrame:
    """상권분석 점포 + interim 경쟁밀도(permit) 병합."""
    perm = pd.read_csv(JOIN / "store_density.csv", dtype=str)
    perm.columns = [c.lstrip("﻿") for c in perm.columns]
    perm = perm[["area_code", "industry", "permit_store_cnt", "store_per_10k_m2"]]
    df = store.merge(perm, on=["area_code", "industry"], how="outer")
    df["permit_store_cnt"] = pd.to_numeric(df["permit_store_cnt"], errors="coerce")
    df["store_per_10k_m2"] = pd.to_numeric(df["store_per_10k_m2"], errors="coerce")
    df["quarter"] = df["quarter"].fillna("20261")
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


def _data_source_meta() -> pd.DataFrame:
    rows = [
        ("sales", "2026-Q1", "서울 상권분석 추정매출 (분기)",
         "당월매출=분기합/3 환산", "2026-07-21"),
        ("rent", "2026-Q1", "한국부동산원 ○○상권 분기 평균 (추정)",
         "환산임대료 음식점 55㎡ 기준", "2026-07-21"),
        ("premium", "2025년(전년 기준)", "한국부동산원 권리금 연간 조사(전년 기준)",
         "서울 숙박·음식점업", "2026-07-21"),
        ("transit", "2026-07", "서울 지하철 승하차 (일평균)",
         "환승역 정규명 합산", "2026-07-21"),
        ("interior", "2025", "공정위 가맹정보 2025 (가맹점 기준·상향, 만원)",
         "인테리어·시설 프록시", "2026-07-24"),
    ]
    return pd.DataFrame(rows, columns=["source", "as_of", "label", "note", "collected_on"])


# ── 파생: 초기비용 + 점수 ────────────────────────────────────────────────────
def _derive(
    rent, est, floating, resident, worker, density, change
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """초기비용(area×industry) + 점수 metrics(교통 유입 제외)."""
    seoul_median_rent = int(rent["monthly_rent"].median())
    rent_df = pd.concat(
        [rent[["area_code", "unit_price"]].assign(industry=ind) for ind in INDUSTRIES],
        ignore_index=True)
    initial_cost = cost.build_initial_cost(rent_df, seoul_median_rent)

    rent_by_area = rent.set_index("area_code")["monthly_rent"].to_dict()
    flo = floating.set_index("area_code")["daily_floating"].to_dict()
    res = resident.set_index("area_code")["resident_pop"].to_dict()
    wrk = worker.set_index("area_code")["worker_pop"].to_dict()
    growth = change.set_index("area_code")["change_code"].map(GROWTH_RANK).to_dict()
    den = density.set_index(["area_code", "industry"])["permit_store_cnt"].to_dict()
    metrics = []
    for (area, ind), es in est.items():
        mr = rent_by_area.get(area)
        if mr is None:
            continue
        metrics.append({
            "area_code": area, "industry": ind,
            "pedestrian": flo.get(area, 0), "backing": res.get(area, 0) + wrk.get(area, 0),
            "est_sales": es, "monthly_rent": mr,
            "density": den.get((area, ind)) or 0, "growth_rank": growth.get(area, 1),
            "daily_floating": flo.get(area, 0), "quarter": "20261",
        })
    return initial_cost, pd.DataFrame(metrics)


def assemble() -> dict[str, pd.DataFrame]:
    logger.info("serving: base 테이블 조립 시작")
    area = _area_master()
    sales, store, est = _sales_and_stores()
    floating = _pop_table("flpop_VwsmTrdarFlpopQq.json", "TOT_FLPOP_CO", "daily_floating")
    resident = _pop_table("repop_VwsmTrdarRepopQq.json", "TOT_REPOP_CO", "resident_pop",
                          {"household_cnt": "TOT_HSHLD_CO"})
    worker = _pop_table("wrcpop_VwsmTrdarWrcPopltnQq.json", "TOT_WRC_POPLTN_CO", "worker_pop")
    change = _change_index()
    rent = _build_rent(area)
    density = _store_density(store, area)
    transit = _transit(area)

    initial_cost, metrics = _derive(rent, est, floating, resident, worker, density, change)
    # 교통 유입 주입: 역 미매칭(폴백) 상권은 유입 0 (거리 ∞) 처리 (스펙 §3-1)
    tr = transit[["area_code", "daily_riders", "distance_m"]].rename(
        columns={"daily_riders": "riders"})
    metrics = metrics.merge(tr, on="area_code", how="left")
    metrics["riders"] = metrics["riders"].fillna(0)
    metrics["distance_m"] = metrics["distance_m"].fillna(10**9)
    location_score = score.build_location_score(metrics)

    # initial_cost: DDL 정합 — 내부용 monthly_rent 제거, based_on_quarter 추가
    initial_cost = initial_cost.drop(columns=["monthly_rent"]).assign(based_on_quarter="20261")

    tables = {
        "data_source_meta": _data_source_meta(),
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
