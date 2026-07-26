import Reveal from '../../components/landing/Reveal'
import { DATA_SOURCES, DATA_TABLE, PIPELINE } from './landingData'
import styles from './DataSection.module.css'

/**
 * 데이터 출처 — GNB "데이터 출처"가 가리키는 곳.
 *
 * 로고만 나열하면 "어디서 받았는지"는 알아도 "무엇을 어떻게 쓰는지"는 알 수 없다.
 * 이 서비스의 신뢰는 **수치가 어디서 와서 어떻게 계산되는지**에 걸려 있으므로,
 * 적재 목록과 계산 경로를 같은 자리에서 보여준다.
 *
 * 기관 로고 SVG가 준비되면 하단 스트립의 워드마크를 `<img>`로 바꾸면 된다 —
 * 회색조 처리와 정렬은 CSS가 이미 맡고 있다.
 */
export default function DataSection() {
  return (
    <section className={styles.section} id="sources">
      <Reveal className={styles.head}>
        <span className={`t-label ${styles.eyebrow}`}>데이터 출처</span>
        <h2 className={styles.title}>무엇을 쓰고, 어떻게 계산하는가</h2>
        <p className={styles.sub}>
          전부 공개 데이터입니다. 화면의 모든 수치에는 출처와 데이터 기준일이 함께 표기됩니다.
        </p>
      </Reveal>

      <Reveal className={styles.tableWrap} delay={80}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">데이터</th>
              <th scope="col">출처</th>
              <th scope="col">갱신</th>
              <th scope="col">기준일</th>
            </tr>
          </thead>
          <tbody>
            {DATA_TABLE.map((row) => (
              <tr key={row.what}>
                <td className={styles.what}>{row.what}</td>
                <td className={styles.org}>{row.org}</td>
                <td className={styles.cycle}>{row.cycle}</td>
                <td className={styles.asOf}>{row.asOf}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Reveal>

      <div className={styles.pipeline}>
        {PIPELINE.map((step, i) => (
          <Reveal key={step.title} delay={i * 80} className={styles.stepWrap}>
            <article className={styles.step}>
              <span className={`t-caption ${styles.stepNo}`}>{`0${i + 1}`}</span>
              <h3 className={`t-title2 ${styles.stepTitle}`}>{step.title}</h3>
              <p className={`t-body ${styles.stepDesc}`}>{step.desc}</p>
            </article>
          </Reveal>
        ))}
      </div>

      <Reveal className={styles.stripWrap} delay={80}>
        <ul className={styles.strip}>
          {DATA_SOURCES.map(({ name, via }) => (
            <li key={name} className={styles.item}>
              <span className={styles.name}>{name}</span>
              <span className={`t-caption ${styles.via}`}>{via}</span>
            </li>
          ))}
        </ul>
      </Reveal>
    </section>
  )
}
