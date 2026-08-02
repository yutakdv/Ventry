"""PostgreSQL 적재 + 덤프 내보내기 (AI-05·06).

- 서빙 12테이블(AI-05) → db/init/10_data_core.sql
- 금융상품 구조화(+원문 청크, AI-06) → db/init/20_finance.sql
- 상권·구획 경계(가정 #95·#96) → frontend/public/geo/area-scope.v1.json  ※DB 를 거치지 않는다
- compose 최초 기동 시 자동 적재 (스펙 §8)

실행: python -m batch.load [core|finance|area-scope]   (인자 없으면 core)
"""
from __future__ import annotations

import sys

from batch.paths import REPO_ROOT, logger, setup_logging

DB_INIT = REPO_ROOT / "db" / "init"

# 부모 → 자식 (FK 순서). TRUNCATE 는 emit 이 역순 처리.
CORE_ORDER = [
    "data_source_meta", "commercial_area",
    "sales", "floating_pop", "resident_pop", "worker_pop",
    "store_density", "change_index", "rent", "transit",
    "location_score", "initial_cost",
]


def run_core() -> None:
    from batch.load import emit, serving

    tables = serving.assemble()
    out = DB_INIT / "10_data_core.sql"
    emit.emit_sql(tables, out, CORE_ORDER, header="Ventry 서빙 실데이터 (AI-05, 스펙 §3-1·§4)")
    logger.info("→ %s (%d테이블)", out, len([t for t in CORE_ORDER if len(tables.get(t, [])) > 0]))


def main() -> None:
    setup_logging()
    target = sys.argv[1] if len(sys.argv) > 1 else "core"
    if target == "core":
        run_core()
    elif target == "finance":
        from batch.load import finance

        finance.run()
    elif target == "area-scope":
        from batch.load import area_geojson

        area_geojson.run()
    else:
        raise SystemExit(f"load: 알 수 없는 대상 {target!r} — 가능: core, finance, area-scope")


if __name__ == "__main__":
    main()
