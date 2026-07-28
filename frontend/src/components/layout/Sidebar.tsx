import { Check } from 'lucide-react'
import styles from './Sidebar.module.css'

const STEPS = [
  { n: 1, title: '자금 진단', desc: '기본 정보 입력' },
  // "가능한 자금 조달" 류의 표현은 쓰지 않는다 — 한도·승인은 기관 심사 사항이라
  // 화면이 조달 가능 여부를 단정하면 안 된다(CLAUDE.md 용어 컴플라이언스). 전 화면 상시 노출.
  { n: 2, title: '조달 시나리오', desc: '보수·적극 2안 비교' },
  { n: 3, title: '예산 선택', desc: '시나리오와 예산 범위' },
  { n: 4, title: '입지 추천', desc: '예산 내 가능한 입지' },
]

/** 좌측 사이드바 — 태그라인 + 4단계 진행. activeStep으로 현재 단계 강조. 전 화면 공용. */
export default function Sidebar({ activeStep = 1 }: { activeStep?: number }) {
  return (
    <aside className={styles.sidebar}>
      <div className={styles.tagline}>
        <p className={`t-title2 ${styles.headline}`}>
          내 형편에
          <br />
          어디까지 가능한가?
        </p>
        <p className={`t-body-strong ${styles.accent}`}>자금이 입지를 결정합니다.</p>
      </div>

      <ol className={styles.steps}>
        {STEPS.map((s) => {
          const state = s.n === activeStep ? 'active' : s.n < activeStep ? 'done' : 'todo'
          return (
            <li
              key={s.n}
              className={`${styles.step} ${styles[state]}`}
              aria-current={state === 'active' ? 'step' : undefined}
            >
              <span className={styles.num}>
                {state === 'done' ? <Check size={14} strokeWidth={3} aria-hidden /> : s.n}
              </span>
              {state === 'done' && <span className={styles.srOnly}>완료</span>}
              <span className={styles.stepText}>
                <span className={`t-body-strong ${styles.stepTitle}`}>{s.title}</span>
                <span className={`t-caption ${styles.stepDesc}`}>{s.desc}</span>
              </span>
            </li>
          )
        })}
      </ol>

      <div className={styles.note}>
        <p className={`t-label ${styles.noteTitle}`}>서비스 구조</p>
        <p className={`t-caption ${styles.noteBody}`}>자금 진단 → 시뮬레이션 → 필터링 → 입지 추천</p>
      </div>
    </aside>
  )
}
