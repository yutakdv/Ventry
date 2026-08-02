-- =============================================================================
-- Ventry 스키마 (AI-03a, 스펙 §3-1)
-- PostgreSQL 16 · docker-entrypoint-initdb.d 에서 사전순 자동 실행
--
-- 규약 (스펙 §0-4 · docs/API_CONTRACT.md 공통 규약)
--   - 좌표는 전부 WGS84 (lat, lng)
--   - 금액은 만원 단위 정수. 예외는 컬럼 주석에 단위를 명시한다
--   - 부담률은 컬럼으로 굽지 않는다 — BE가 monthly_rent ÷ est_sales 로 파생 (§4-2, assumptions #8)
--   - 정규화 전 원값(환산임대료·월 추정매출·일평균 유동인구)은 서빙 테이블에 그대로 적재
--     한다 — 화면이 출처·해상도와 함께 원값을 표기해야 하므로 정규화값만 남기지 않는다
--
-- 구성
--   [1] 메타       data_source_meta
--   [2] 마스터     commercial_area
--   [3] 분기 이력  sales · floating_pop · resident_pop · worker_pop · store_density · change_index
--   [4] 서빙 현행  rent · transit · location_score · initial_cost   (AI-04·AI-05 산출)
--   [5] 금융상품   finance_product                                   (AI-06 산출)
--   [6] 서빙 뷰    v_candidate_area                                  (BE "탐색당 쿼리 1회")
-- =============================================================================

-- =============================================================================
-- [1] 데이터 기준일 메타 — 화면·API `data_as_of` 표기의 단일 원천 (스펙 §0-4)
-- =============================================================================
CREATE TABLE data_source_meta (
    source       TEXT PRIMARY KEY,   -- 예: 'sales', 'rent', 'premium', 'transit'
    as_of        TEXT NOT NULL,      -- 화면 표기 문자열 그대로. 예: '2026-Q1', '2025년(전년 기준)'
    label        TEXT NOT NULL,      -- 화면 출처 라벨. 예: '한국부동산원 ○○상권 분기 평균 (추정)'
    note         TEXT,
    collected_on DATE NOT NULL
);

COMMENT ON TABLE data_source_meta IS
    '데이터 기준일 상시 표기의 공급처. 권리금만 주기가 연간이라 as_of 문구가 다르다 (assumptions #1).';

-- =============================================================================
-- [2] 상권 마스터 — 서울 상권분석서비스 상권코드(TRDAR_CD) 기준
-- =============================================================================
CREATE TABLE commercial_area (
    area_code      TEXT PRIMARY KEY,          -- TRDAR_CD
    name           TEXT NOT NULL,             -- TRDAR_CD_NM
    area_type_code TEXT,                      -- TRDAR_SE_CD  (A 골목/D 발달/R 전통시장/U 관광특구)
    area_type_name TEXT,
    sigungu_code   TEXT,
    sigungu_name   TEXT NOT NULL,             -- 자치구 — BE 캐시 키이자 임대료 폴백 그룹 키
    adstrd_code    TEXT,
    adstrd_name    TEXT,
    lat            DOUBLE PRECISION NOT NULL, -- WGS84 (EPSG:5181 중심점 변환값)
    lng            DOUBLE PRECISION NOT NULL,
    area_m2        INTEGER                    -- RELM_AR
);

CREATE INDEX idx_commercial_area_sigungu ON commercial_area (sigungu_name);

-- =============================================================================
-- [3] 분기 이력 — 원천 지표. quarter 는 서울 OpenAPI STDR_YYQU_CD ('20261' = 2026년 1분기)
-- =============================================================================

-- 추정매출 (구매력 w2 · 부담률 분모의 원천)
CREATE TABLE sales (
    area_code         TEXT NOT NULL REFERENCES commercial_area (area_code),
    quarter           TEXT NOT NULL,
    industry          TEXT NOT NULL CHECK (industry IN ('cafe', 'food')),
    industry_code     TEXT,                   -- SVC_INDUTY_CD (예: CS100010 커피-음료)
    monthly_sales     BIGINT NOT NULL,        -- 만원 단위. THSMON_SELNG_AMT 환산
    monthly_sales_cnt BIGINT,                 -- 건수
    PRIMARY KEY (area_code, quarter, industry)
);

-- 길단위인구(유동) — w1 수요 1성분
CREATE TABLE floating_pop (
    area_code      TEXT NOT NULL REFERENCES commercial_area (area_code),
    quarter        TEXT NOT NULL,
    daily_floating INTEGER NOT NULL,          -- 명/일. TOT_FLPOP_CO
    PRIMARY KEY (area_code, quarter)
);

-- 상주인구 — w1 배후 성분
CREATE TABLE resident_pop (
    area_code     TEXT NOT NULL REFERENCES commercial_area (area_code),
    quarter       TEXT NOT NULL,
    resident_pop  INTEGER NOT NULL,           -- TOT_REPOP_CO
    household_cnt INTEGER,                    -- TOT_HSHLD_CO
    PRIMARY KEY (area_code, quarter)
);

-- 직장인구 — w1 배후 성분
CREATE TABLE worker_pop (
    area_code  TEXT NOT NULL REFERENCES commercial_area (area_code),
    quarter    TEXT NOT NULL,
    worker_pop INTEGER NOT NULL,              -- TOT_WRC_POPLTN_CO
    PRIMARY KEY (area_code, quarter)
);

-- 경쟁밀도 — w3 경쟁여유. permit_store_cnt 는 인허가 점-폴리곤 조인 산출 (AI-04b)
CREATE TABLE store_density (
    area_code          TEXT NOT NULL REFERENCES commercial_area (area_code),
    quarter            TEXT NOT NULL,
    industry           TEXT NOT NULL CHECK (industry IN ('cafe', 'food')),
    store_cnt          INTEGER,               -- STOR_CO
    similar_store_cnt  INTEGER,               -- SIMILR_INDUTY_STOR_CO
    open_rate          NUMERIC(6, 3),         -- OPBIZ_RT (%)
    close_rate         NUMERIC(6, 3),         -- CLSBIZ_RT (%)
    permit_store_cnt   INTEGER,               -- 인허가 영업중 업소 수 (AI-04b)
    store_per_10k_m2   NUMERIC(10, 3),        -- 면적 정규화 밀도
    PRIMARY KEY (area_code, quarter, industry)
);

-- 상권변화지표 — w4 성장성
CREATE TABLE change_index (
    area_code        TEXT NOT NULL REFERENCES commercial_area (area_code),
    quarter          TEXT NOT NULL,
    change_code      TEXT,                    -- TRDAR_CHNGE_IX (LL/LH/HL/HH)
    change_name      TEXT,                    -- TRDAR_CHNGE_IX_NM
    oper_avg_months  NUMERIC(8, 2),           -- OPR_SALE_MT_AVRG
    close_avg_months NUMERIC(8, 2),           -- CLS_SALE_MT_AVRG
    PRIMARY KEY (area_code, quarter)
);

-- =============================================================================
-- [4] 서빙 현행 — 상권당 1행(또는 상권×업종 1행). AI-04·AI-05 배치 산출물
-- =============================================================================

-- 임대료·전환율 (부담률 분자 · 보증금 역산)
-- 해상도 혼합 주의: 임대료는 부동산원 광역 상권 단위, 매출·유동은 상권 단위 (스펙 §2-1)
CREATE TABLE rent (
    area_code         TEXT PRIMARY KEY REFERENCES commercial_area (area_code),
    quarter           TEXT NOT NULL,
    monthly_rent      INTEGER NOT NULL,       -- 환산임대료 만원/월 — 정규화 전 원값
    unit_price        NUMERIC(12, 4),         -- 원 단위 그대로: 천원/㎡ (R-ONE DTA_VAL)
    convert_rate      NUMERIC(6, 3),          -- 전월세 전환율 (%)
    vacancy_rate      NUMERIC(6, 3),          -- 공실률 (%)
    reb_district_cd   TEXT,                   -- R-ONE CLS_ID
    reb_district_name TEXT,                   -- R-ONE 상권명 (예: '홍대/합정')
    reb_store_type    TEXT CHECK (reb_store_type IN ('small', 'medium', 'complex')),
    fallback_flag     BOOLEAN NOT NULL DEFAULT FALSE,  -- true = 자치구 평균 대체 (AI-04c)
    source_org        TEXT NOT NULL DEFAULT 'REB'
);

COMMENT ON COLUMN rent.reb_store_type IS
    '할당 우선순위 small → medium → complex. 세 유형 합집합이 서울 72개 상권 전건 커버 (assumptions #12).';

-- 최근접 지하철역 (w1 교통 접근성 성분의 재료 d·V)
CREATE TABLE transit (
    area_code       TEXT PRIMARY KEY REFERENCES commercial_area (area_code),
    nearest_station TEXT,
    line            TEXT,
    distance_m      INTEGER,
    daily_riders    INTEGER,                  -- 환승역 정규명 합산 일평균 (assumptions #4)
    fallback_flag   BOOLEAN NOT NULL DEFAULT FALSE  -- true = 접근성 성분 0 처리 (AI-04d)
);

-- 점수화 결과 + 화면 표기용 원값 (스펙 §4-3)
CREATE TABLE location_score (
    area_code        TEXT NOT NULL REFERENCES commercial_area (area_code),
    industry         TEXT NOT NULL CHECK (industry IN ('cafe', 'food')),
    w1               DOUBLE PRECISION NOT NULL,  -- 수요   [0,1] 서울 전체 백분위
    w2               DOUBLE PRECISION NOT NULL,  -- 구매력
    w3               DOUBLE PRECISION NOT NULL,  -- 경쟁여유
    w4               DOUBLE PRECISION NOT NULL,  -- 성장성
    w5               DOUBLE PRECISION NOT NULL,  -- 비용효율
    est_sales        INTEGER NOT NULL,           -- 월 추정매출 만원 — 정규화 전 원값
    daily_floating   INTEGER NOT NULL,           -- 일평균 유동인구 명 — 정규화 전 원값
    based_on_quarter TEXT NOT NULL,
    PRIMARY KEY (area_code, industry)
);

COMMENT ON TABLE location_score IS
    '종합점수는 저장하지 않는다 — 가중치가 업종 프리셋이라 BE가 Σ wᵢ·축ᵢ 로 계산한다 (assumptions #8).';
COMMENT ON COLUMN location_score.daily_floating IS
    '상권 단위 값이라 업종 행마다 중복된다. 서빙 단일 그레인 유지를 위한 의도적 비정규화.';

-- 초기비용 4블록 (스펙 §4-1) — 전 블록 구간, 권리금 이중 표기
CREATE TABLE initial_cost (
    area_code             TEXT NOT NULL REFERENCES commercial_area (area_code),
    industry              TEXT NOT NULL CHECK (industry IN ('cafe', 'food')),
    monthly_rent          INTEGER NOT NULL,   -- 업종 대표면적 기준 환산임대료 만원/월 (부담률 분자)
    deposit_low           INTEGER NOT NULL,
    deposit_high          INTEGER NOT NULL,
    premium_low           INTEGER NOT NULL,
    premium_high          INTEGER NOT NULL,
    interior_low          INTEGER NOT NULL,
    interior_high         INTEGER NOT NULL,
    monthly_fixed_cost    INTEGER NOT NULL,   -- 예비 운영자금 = ×6개월 (여신 관행 버퍼)
    cost_ex_premium_low   INTEGER NOT NULL,   -- 권리금 제외 합계 구간
    cost_ex_premium_high  INTEGER NOT NULL,
    cost_incl_premium_low INTEGER NOT NULL,   -- 권리금 포함 합계 구간
    cost_incl_premium_high INTEGER NOT NULL,
    based_on_quarter      TEXT NOT NULL,
    PRIMARY KEY (area_code, industry),
    CONSTRAINT initial_cost_interval_order CHECK (
        deposit_low <= deposit_high AND premium_low <= premium_high
        AND interior_low <= interior_high
        AND cost_ex_premium_low <= cost_ex_premium_high
        AND cost_incl_premium_low <= cost_incl_premium_high
    )
);

COMMENT ON COLUMN initial_cost.monthly_rent IS
    '업종 대표면적(카페 44.0㎡ · 음식점 51.7㎡ — 인허가 소재지면적 영업중 중앙값, 가정 #41 ①·#84) 기준 환산임대료. rent.monthly_rent 는 상권 단위 표기값(음식점 대표면적 기준)이라 업종별 부담률 분자로 쓰면 카페가 과대해진다 (리뷰 #2).';

-- BE 프론티어 사전 정렬 인덱스 — 예산 경계 탐색이 정렬 배열 위 이분 탐색이다 (expl §2-1)
CREATE INDEX idx_initial_cost_sort_incl
    ON initial_cost (industry, cost_incl_premium_low, cost_incl_premium_high);
CREATE INDEX idx_initial_cost_sort_ex
    ON initial_cost (industry, cost_ex_premium_low, cost_ex_premium_high);

-- =============================================================================
-- [5] 금융상품 — BE FundingProduct·Eligibility 레코드와 1:1 (AI-06 구조화 산출)
--     NULL = 해당 축 무제약 (Eligibility 의미론 그대로)
-- =============================================================================
CREATE TABLE finance_product (
    product_id       TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    org              TEXT NOT NULL,
    max_age          INTEGER,                 -- 상한 연령(포함). NULL = 연령 무제약
    industries       TEXT[],                  -- NULL/빈 배열 = 전 업종
    regions          TEXT[],                  -- NULL/빈 배열 = 전 지역
    pre_startup_only BOOLEAN NOT NULL DEFAULT FALSE,
    -- 기존 사업자 한정 — pre_startup_only 의 반대 축이다 (이슈 #90 문제 2).
    -- 공고문이 '보유한 대출'(대환) 또는 '재창업'을 명시한 상품은 예비창업자가 신청할 수 없다.
    -- 축이 없던 동안 예비창업 프로필에 대환대출이 편성됐다 — 상품명 블랙리스트로 코드에
    -- 숨기는 대신 자격 축으로 표현한다.
    existing_business_only BOOLEAN NOT NULL DEFAULT FALSE,
    -- 프로필로 확인할 수 없는 **대상 한정 요건** (장애인기업·사회적경제기업·인증기업 등).
    -- 5종 축(연령·업종·지역·예비창업·기존사업자)으로는 공고문의 대상 요건을 표현할 수 없어
    -- 신청 자격이 없는 상품이 "자격 요건 부합"으로 노출됐다 (BE 리뷰 D-06).
    -- 판정 규칙은 regions 와 동형의 **하향 안전**: 값이 있으면 프로필이 그 표시를 갖지 않는 한
    -- 탈락시킨다 — 확인하지 못한 자격을 주장하지 않는다. NULL/빈 배열 = 대상 제한 없음.
    target_group     TEXT[],
    amount_max       INTEGER NOT NULL,        -- 만원
    rate             NUMERIC(6, 3),           -- 연 %. 고정금리 숫자값. 단일 숫자로 표기 불가면 NULL → rate_note 참조.
                                              -- ⚠️ NULL ≠ variable: fixed도 숫자 미표기 시 NULL 가능(분기는 rate_type로, API_CONTRACT 금리 표기)
    rate_type        TEXT NOT NULL DEFAULT 'fixed'   -- 항상 존재(FE 분기 키)
                     CHECK (rate_type IN ('fixed', 'variable')),
    rate_note        TEXT,                    -- 변동금리 원문 표현("정책자금 기준금리+0.6%p" 등).
                                              -- LLM 재작성 금지(원문 그대로). 기준금리 실값 미주입
    term_months      INTEGER,                 -- NULL = 상품 조건 미정 (BE-04가 보증 가정 부여)
    exclusive_group  TEXT,                    -- 동일 그룹 1개만 (중복수혜 제약). NULL = 제약 없음
    status           TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    notice_date      DATE,
    data_as_of       TEXT NOT NULL,
    source_org       TEXT NOT NULL,
    source_url       TEXT NOT NULL,
    source_collected DATE NOT NULL,
    doc_chunk_ref    TEXT                     -- 벡터DB 원문 청크 키 (BE-06 RAG source_quote)
);

CREATE INDEX idx_finance_product_open ON finance_product (status, amount_max DESC);

COMMENT ON TABLE finance_product IS
    '자격 요건 부합 판정용 정규칙만 담는다. 한도·승인은 기관 심사 사항이며 서비스는 판단하지 않는다.';

-- 원문 문단 청크 — source_quote 는 doc_chunk_ref 직접 조회 (pgvector 미도입, AI-06 결정)
-- LLM 재작성 금지: text 는 공고문 원문 그대로 (인용은 검색이지 생성이 아니다, 스펙 §5-4)
CREATE TABLE finance_doc_chunk (
    chunk_id   TEXT PRIMARY KEY,
    product_id TEXT REFERENCES finance_product (product_id),
    doc_meta   JSONB NOT NULL,          -- {org, doc, date, para} 화면 출처 표기용
    text       TEXT NOT NULL            -- 원문 문단 (BE-06 RAG source_quote 원천)
);

CREATE INDEX idx_finance_doc_chunk_product ON finance_doc_chunk (product_id);

COMMENT ON TABLE finance_doc_chunk IS
    'finance_product.doc_chunk_ref 가 가리키는 원문 청크. text 는 LLM 재작성 없이 공고문 원문 그대로.';

-- =============================================================================
-- [6] 서빙 뷰 — BE CandidateArea 1행에 대응. "탐색당 쿼리 1회" 원칙 (expl §5)
--     부담률·종합점수는 여기서도 굽지 않는다 (BE 파생).
-- =============================================================================
CREATE VIEW v_candidate_area AS
SELECT
    a.area_code,
    a.name,
    a.sigungu_name,
    a.lat,
    a.lng,
    s.industry,
    c.deposit_low, c.deposit_high,
    c.premium_low, c.premium_high,
    c.interior_low, c.interior_high,
    c.monthly_fixed_cost,
    c.cost_ex_premium_low, c.cost_ex_premium_high,
    c.cost_incl_premium_low, c.cost_incl_premium_high,
    s.w1, s.w2, s.w3, s.w4, s.w5,
    c.monthly_rent,
    s.est_sales,
    s.daily_floating,
    r.source_org        AS rent_org,
    r.reb_district_name AS rent_district,
    r.fallback_flag     AS rent_fallback,
    t.nearest_station   AS transit_station,
    t.line              AS transit_line,
    t.distance_m        AS transit_distance_m,
    t.daily_riders      AS transit_daily_riders,
    t.fallback_flag     AS transit_fallback
FROM location_score s
JOIN commercial_area a ON a.area_code = s.area_code
JOIN initial_cost   c ON c.area_code = s.area_code AND c.industry = s.industry
JOIN rent           r ON r.area_code = s.area_code
LEFT JOIN transit   t ON t.area_code = s.area_code;
