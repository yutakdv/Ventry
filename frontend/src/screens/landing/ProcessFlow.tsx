import { ChevronRight } from 'lucide-react'
import Reveal from '../../components/landing/Reveal'
import { PROCESS } from './landingData'
import styles from './ProcessFlow.module.css'

/**
 * AI 개입 지점을 사용자 동선 순서의 흐름으로 보여준다.
 *
 * 카드 4장을 나열하면 "기능 목록"으로 읽히지만, 화살표로 이으면 **순서가 있는 절차**가 된다.
 * 이 서비스의 요지가 「진단 → 예산 → 탐색 → 판정」이라는 순서 자체라서 흐름으로 그린다.
 */
export default function ProcessFlow() {
  return (
    <ol className={styles.flow}>
      {PROCESS.map(({ icon: Icon, no, title, desc }, i) => (
        <Reveal as="li" key={no} delay={i * 90} className={styles.item}>
          <div className={styles.step}>
            <div className={styles.head}>
              <span className={styles.icon}>
                <Icon size={20} aria-hidden />
              </span>
              <span className={`t-caption ${styles.no}`}>{no}</span>
            </div>
            <h3 className={`t-title2 ${styles.title}`}>{title}</h3>
            <p className={`t-body ${styles.desc}`}>{desc}</p>
          </div>
          {i < PROCESS.length - 1 && (
            <span className={styles.arrow} aria-hidden>
              <ChevronRight size={18} />
            </span>
          )}
        </Reveal>
      ))}
    </ol>
  )
}
