import Reveal from '../../components/landing/Reveal'
import { KPIS } from './landingData'
import styles from './KpiBand.module.css'

/** 실측 지표 4종. 값은 `landingData.ts`에 근거와 함께 적혀 있다. */
export default function KpiBand() {
  return (
    <section className={styles.band}>
      <div className={styles.grid}>
        {KPIS.map(({ icon: Icon, label, value, note }, i) => (
          <Reveal key={label} delay={i * 70} className={styles.cardWrap}>
            <article className={styles.card}>
              <span className={styles.icon}>
                <Icon size={22} aria-hidden />
              </span>
              <span className={`t-caption ${styles.label}`}>{label}</span>
              <span className={styles.value}>{value}</span>
              <span className={`t-caption ${styles.note}`}>{note}</span>
            </article>
          </Reveal>
        ))}
      </div>
    </section>
  )
}
