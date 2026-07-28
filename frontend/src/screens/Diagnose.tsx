import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import AppShell from '../components/layout/AppShell'
import RailCard from '../components/layout/RailCard'
import Button from '../components/Button'
import Field from '../components/Field'
import TextInput from '../components/TextInput'
import Select from '../components/Select'
import SegmentedChoice from '../components/SegmentedChoice'
import InfoBanner from '../components/InfoBanner'
import Divider from '../components/Divider'
import Alert from '../components/Alert'
import MyDataPanel from './MyDataPanel'
import ParsedResult from './ParsedResult'
import { postDiagnose } from '../api/client'
import { useSession } from '../store/session'
import type { DiagnoseRequest, DiagnoseResponse, Industry } from '../api/types'
import {
  SIDO,
  SEOUL_GU,
  INDUSTRY,
  START_TIMING,
  OP_TYPE,
  AREA_TYPE,
  DEMO_PROFILE,
  EMPTY_FORM,
  validateDiagnose,
  type FormState,
  type YesNo,
  type RequiredField,
} from './diagnoseData'
import styles from './Diagnose.module.css'

/** 원(문자열) → 만원 정수. 계약 금액 단위(만원)로 환산. */
function wonToManwon(won: string): number | null {
  const n = parseInt(won, 10)
  return Number.isFinite(n) ? Math.round(n / 10000) : null
}

/** 콤마 표시용. */
function comma(digits: string): string {
  return digits ? Number(digits).toLocaleString('ko-KR') : ''
}

function buildRequest(f: FormState): DiagnoseRequest {
  const sidoLabel = SIDO.find((s) => s.value === f.sido)?.label
  const region_hint = f.gu && sidoLabel ? `${sidoLabel} ${f.gu}` : null

  // 선택 3필드는 계약 form에 없어 free_text 맥락으로(범주형 → §0-1 무관, 탐색 우선순위용).
  const ctx: string[] = []
  if (f.startTiming) ctx.push(`창업 희망 시기: ${f.startTiming}`)
  if (f.opType) ctx.push(`희망 운영 형태: ${f.opType}`)
  if (f.areaType && f.areaType !== '상관없음') ctx.push(`상권 유형: ${f.areaType}`)
  const free_text = [f.freeText.trim(), ctx.length ? `[추가 정보] ${ctx.join(' / ')}` : '']
    .filter(Boolean)
    .join('\n')

  return {
    form: {
      age: f.age ? parseInt(f.age, 10) : null,
      capital: wonToManwon(f.capitalWon),
      is_existing_business: f.isExisting == null ? null : f.isExisting === 'yes',
      collateral_available: f.collateral == null ? null : f.collateral === 'yes',
      monthly_investable: wonToManwon(f.monthlyWon),
      industry: f.industry || null,
      region_hint,
    },
    free_text,
  }
}

export default function Diagnose() {
  const navigate = useNavigate()
  const { setSession, diagnoseForm, setDiagnoseForm } = useSession()
  const [params] = useSearchParams()
  /*
   * 화면 2에서 "입력 정보 수정"으로 돌아오면 직전 입력값이 그대로 복원된다.
   * 랜딩의 "3분 데모 체험"은 `?demo=1`로 들어오며 데모 프로필이 채워진 상태로 시작한다 —
   * 직전 입력이 있으면 그쪽을 우선한다(되돌아온 사용자의 입력을 덮지 않는다).
   */
  const [f, setF] = useState<FormState>(
    diagnoseForm ?? (params.get('demo') === '1' ? { ...EMPTY_FORM, ...DEMO_PROFILE } : EMPTY_FORM),
  )
  const [submitted, setSubmitted] = useState(false) // "조달 시나리오 보기" 누른 뒤 에러 노출
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<DiagnoseResponse | null>(null)

  // 필수 검증 — 폼 상태에서 매 렌더 계산(별도 상태 없음 → 채우면 즉시 에러 해제).
  const errors = validateDiagnose(f)
  const errorCount = Object.keys(errors).length
  const isValid = errorCount === 0
  /** 제출을 시도한 뒤에만 에러를 노출한다(입력 중에는 빨간칠하지 않음). 시도 후엔 채우면 즉시 사라짐. */
  const fieldError = (k: RequiredField) => (submitted ? errors[k] : undefined)

  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => {
    setF((p) => ({ ...p, [k]: v }))
  }

  function fillDemo() {
    setF({ ...EMPTY_FORM, ...DEMO_PROFILE })
  }

  async function submit() {
    if (!isValid) {
      setSubmitted(true) // 미입력 필드 에러 노출 + 이동 차단
      window.scrollTo({ top: 0, behavior: 'smooth' }) // 상단 에러 요약으로 스크롤
      return
    }
    setLoading(true)
    try {
      const res = await postDiagnose(buildRequest(f))
      setDiagnoseForm(f) // 다시 돌아왔을 때 복원할 원본 입력값
      setSession(res.session_id, res.parsed_profile)
      setResult(res)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } finally {
      setLoading(false)
    }
  }

  const aside = (
    <>
      <RailCard
        tone="brand"
        title="마이데이터 연동 안내"
        items={[
          // 실제 금융기관 연동은 이 프로토타입에 없다. 있다고 적으면 화면을 만져 보는 순간
          // 반증되므로, 연동 이후를 전제한 문장이 아니라 지금 실제로 하는 일을 적는다.
          '이 프로토타입에서는 데모 프로필 값으로 폼을 채웁니다 (실제 금융기관 연동 없음).',
          '입력한 정보는 자금 진단 계산에만 사용되며 서버에 저장하지 않습니다.',
          '불러온 항목은 모두 직접 수정할 수 있습니다.',
        ]}
      />
      <RailCard
        title="입력 예시"
        marker="dot"
        items={[
          '자기자본: 현재 보유하고 있는 현금/예금',
          '나이: 정책자금 자격(청년 등) 판정에 사용',
          '월 투자 가능액: 인건비·재료비 등 매월 감당 가능한 운영·상환액',
          '담보 가능 여부: 보증·담보 대출 한도 판정에 사용',
        ]}
      />
      <RailCard
        title="안내 사항"
        items={[
          '예상 조달 시나리오이며 실제 대출 가능 금액을 보장하지 않습니다.',
          '신용 점수, 소득 등 개인 금융 정보는 직접 입력하지 않습니다.',
          '금융상품 정보는 공식 자료를 기준으로 제공합니다.',
        ]}
      />
      <RailCard
        title="데이터 출처"
        items={[
          '정책자금 소상공인시장진흥공단 공고',
          '보증 지역신용보증재단 상품 안내',
          '대출 KB국민은행 및 타 금융기관 상품 안내',
        ]}
        actionLabel="출처 자세히 보기"
        onAction={() => navigate('/#sources')}
      />
    </>
  )

  return (
    <AppShell activeStep={1} aside={aside}>
      {result ? (
        <ParsedResult
          profile={result.parsed_profile}
          sessionId={result.session_id}
          onEdit={() => setResult(null)}
          onProceed={() => navigate('/scenarios')}
        />
      ) : (
        <div className={styles.surface}>
          <div className={styles.headerRow}>
            <h1 className="t-title1">1단계. 자금 진단</h1>
            {/*
              "ⓘ 마이데이터란?"은 `href="#"` 이라 눌러도 아무 일이 없었다. 설명은 우측 레일의
              「마이데이터 연동 안내」 카드가 이미 하고 있으므로 죽은 링크만 걷어낸다.
            */}
          </div>
          <p className={`t-body ${styles.subtitle}`}>
            현재 상황을 입력하면, 정책자금·보증·대출을 조합한 조달 시나리오와 예산 범위를 안내해 드립니다.
          </p>

          {submitted && errorCount > 0 && (
            <Alert title={`입력값 ${errorCount}건을 확인해 주세요.`}>
              표시된 항목을 수정하면 조달 시나리오를 계산할 수 있습니다.
            </Alert>
          )}

          <p className={`t-caption ${styles.demoHint}`}>
            예비창업자 · 만 32세 · 자기자본 5,000만원 · 마포 카페 — 마이데이터 불러오기를 통해 데모프로필을 한 번에 채웁니다
          </p>

          <InfoBanner tone="info">입력하신 정보는 안전하게 보호되며, 진단 결과에만 활용됩니다.</InfoBanner>

          <div className={styles.mydata}>
            <MyDataPanel onImport={fillDemo} />
          </div>

          {/* 1. 기본 정보 */}
          <h2 className={`t-title2 ${styles.section}`}>1. 기본 정보 (필수)</h2>
          <div className={styles.row3}>
            <Field label="업종" error={fieldError('industry')}>
              <Select
                placeholder="업종을 선택하세요"
                invalid={!!fieldError('industry')}
                value={f.industry}
                onChange={(e) => set('industry', e.target.value as Industry)}
              >
                {INDUSTRY.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="사업자 여부" error={fieldError('isExisting')}>
              <SegmentedChoice<YesNo>
                options={[
                  { value: 'yes', label: '기존 사업자' },
                  { value: 'no', label: '예비 창업자' },
                ]}
                value={f.isExisting}
                invalid={!!fieldError('isExisting')}
                onChange={(v) => set('isExisting', v)}
              />
            </Field>
            <Field label="나이 (만)" error={fieldError('age')}>
              <TextInput
                inputMode="numeric"
                placeholder="예) 32"
                suffix="세"
                invalid={!!fieldError('age')}
                value={f.age}
                onChange={(e) => set('age', e.target.value.replace(/[^\d]/g, ''))}
              />
            </Field>
          </div>
          <div className={styles.regionField}>
            <Field label="희망 지역" error={submitted ? (errors.sido ?? errors.gu) : undefined}>
              <div className={styles.row2}>
                <Select
                  placeholder="시/도 선택"
                  invalid={!!fieldError('sido')}
                  value={f.sido}
                  onChange={(e) => set('sido', e.target.value)}
                >
                  {SIDO.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </Select>
                <Select
                  placeholder="구/군 선택"
                  invalid={!!fieldError('gu')}
                  value={f.gu}
                  onChange={(e) => set('gu', e.target.value)}
                >
                  {SEOUL_GU.map((g) => (
                    <option key={g} value={g}>
                      {g}
                    </option>
                  ))}
                </Select>
              </div>
            </Field>
          </div>

          <Divider />

          {/* 2. 자금 상황 */}
          <h2 className={`t-title2 ${styles.section}`}>2. 자금 상황 (필수)</h2>
          <div className={styles.row3}>
            <Field label="자기자본 (현금 보유액)" error={fieldError('capitalWon')}>
              <TextInput
                inputMode="numeric"
                placeholder="예) 5,000,000"
                suffix="원"
                invalid={!!fieldError('capitalWon')}
                value={comma(f.capitalWon)}
                onChange={(e) => set('capitalWon', e.target.value.replace(/[^\d]/g, ''))}
              />
            </Field>
            <Field label="월 투자 가능 금액 (운영·상환)" error={fieldError('monthlyWon')}>
              <TextInput
                inputMode="numeric"
                placeholder="예) 2,000,000"
                suffix="원"
                invalid={!!fieldError('monthlyWon')}
                value={comma(f.monthlyWon)}
                onChange={(e) => set('monthlyWon', e.target.value.replace(/[^\d]/g, ''))}
              />
            </Field>
            <Field label="담보 제공 가능 여부" error={fieldError('collateral')}>
              <SegmentedChoice<YesNo>
                options={[
                  { value: 'yes', label: '가능' },
                  { value: 'no', label: '어려움' },
                ]}
                value={f.collateral}
                invalid={!!fieldError('collateral')}
                onChange={(v) => set('collateral', v)}
              />
            </Field>
          </div>
          <InfoBanner tone="tip">
            자기자본과 월 투자(운영) 가능액을 기준으로 자금을 진단합니다. 월 상환 여력을 고려해 현실적인 금액을 입력해 주세요.
          </InfoBanner>

          <Divider />

          {/* 3. 추가 정보 (선택 — 검증 제외) */}
          <h2 className={`t-title2 ${styles.section}`}>3. 추가 정보 (선택)</h2>
          <div className={styles.row3}>
            <Field label="창업 희망 시기">
              <Select
                placeholder="선택하세요"
                value={f.startTiming}
                onChange={(e) => set('startTiming', e.target.value)}
              >
                {START_TIMING.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="희망 운영 형태">
              <Select
                placeholder="선택하세요"
                value={f.opType}
                onChange={(e) => set('opType', e.target.value)}
              >
                {OP_TYPE.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="상권 유형 (선택)">
              <Select
                placeholder="상권 유형 선택"
                value={f.areaType}
                onChange={(e) => set('areaType', e.target.value)}
              >
                {AREA_TYPE.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </Select>
            </Field>
          </div>

          <Divider />

          {/* 4. 자유 입력 (선택 — 검증 제외) */}
          <h2 className={`t-title2 ${styles.section}`}>4. 더 알려주실 내용 (선택)</h2>
          <label className={`t-label ${styles.freeLabel}`} htmlFor="freeText">
            고민이나 상황을 자유롭게 적어주세요
          </label>
          <textarea
            id="freeText"
            className={`t-body ${styles.textarea}`}
            placeholder="예) 권리금이 제일 걱정이에요. 역세권이 아니어도 괜찮습니다."
            value={f.freeText}
            onChange={(e) => set('freeText', e.target.value)}
          />
          <p className={`t-caption ${styles.freeHint}`}>
            ⓘ 문장에서 관심사(예: 권리금)를 파악해 탐색 우선순위에 반영합니다. 금액·나이 같은 수치는 위 폼 값만 사용합니다.
          </p>

          <div className={styles.submit}>
            <Button
              variant="primary"
              size="lg"
              fullWidth
              onClick={submit}
              disabled={loading}
            >
              {loading ? '진단 중…' : '조달 시나리오 보기 →'}
            </Button>
          </div>
        </div>
      )}
    </AppShell>
  )
}
