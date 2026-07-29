import Button from '../components/Button'
import Divider from '../components/Divider'
import type { ParsedProfile } from '../api/types'
import styles from './ParsedResult.module.css'

const INDUSTRY_LABEL: Record<string, string> = { cafe: '카페', food: '음식점' }
const CONCERN_LABEL: Record<string, string> = {
  premium: '권리금',
  rent: '임대료',
  traffic: '유동인구',
}

/** 진단 결과 확인 — parsed_profile을 사람이 읽기 좋게 + parse_source 배지 + 관심사 칩. */
export default function ParsedResult({
  profile,
  sessionId,
  onEdit,
  onProceed,
}: {
  profile: ParsedProfile
  sessionId: string
  onEdit: () => void
  onProceed: () => void
}) {
  const rows: [string, string][] = [
    ['업종', profile.industry ? INDUSTRY_LABEL[profile.industry] : '—'],
    ['희망 지역', profile.region_hint ?? '—'],
    ['나이', profile.age != null ? `만 ${profile.age}세` : '—'],
    [
      '사업자 여부',
      profile.is_existing_business == null
        ? '—'
        : profile.is_existing_business
          ? '기존 사업자'
          : '예비 창업자',
    ],
    ['자기자본', profile.capital != null ? `${profile.capital.toLocaleString()}만원` : '—'],
    [
      '월 투자 가능액',
      profile.monthly_investable != null ? `${profile.monthly_investable.toLocaleString()}만원` : '—',
    ],
    [
      '담보 제공',
      profile.collateral_available == null ? '—' : profile.collateral_available ? '가능' : '어려움',
    ],
  ]

  return (
    <div className={styles.card}>
      <div className={styles.head}>
        <h1 className="t-title1">진단 결과 확인</h1>
        <span
          className={`t-caption ${styles.badge} ${profile.parse_source === 'llm' ? styles.llm : styles.fallback}`}
        >
          {profile.parse_source === 'llm' ? 'AI 파싱 반영' : '폼 값만 사용'}
        </span>
      </div>
      <p className={`t-body ${styles.sub}`}>
        입력하신 내용을 이렇게 이해했어요. 틀린 부분이 있으면 “다시 입력”으로 수정해 주세요.
      </p>
      <Divider />
      <dl className={styles.grid}>
        {rows.map(([k, v]) => (
          <div key={k} className={styles.row}>
            <dt className={`t-label ${styles.key}`}>{k}</dt>
            <dd className={`t-body-strong ${styles.val}`}>{v}</dd>
          </div>
        ))}
      </dl>
      {profile.concerns.length > 0 && (
        <div className={styles.concerns}>
          <span className={`t-label ${styles.key}`}>파악된 관심사</span>
          <div className={styles.chips}>
            {profile.concerns.map((c) => (
              <span key={c} className={`t-caption ${styles.chip}`}>
                {CONCERN_LABEL[c] ?? c}
              </span>
            ))}
          </div>
        </div>
      )}
      <div className={styles.actions}>
        <Button variant="secondary" size="lg" onClick={onEdit}>
          다시 입력
        </Button>
        <Button variant="primary" size="lg" fullWidth onClick={onProceed}>
          이대로 조달 시나리오 보기 →
        </Button>
      </div>
      {/*
        세션 id 는 디버깅에만 쓸모가 있다. 심사 화면에서는 의미 없는 36자 문자열이 결과
        바로 아래에 놓여 시선을 뺏는다 (FE 리뷰 UX-7). 개발 모드에서만 남긴다.
      */}
      {import.meta.env.DEV && <p className={`t-caption ${styles.sid}`}>세션 {sessionId}</p>}
    </div>
  )
}
