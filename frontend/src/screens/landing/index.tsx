import GNBHeader from '../../components/layout/GNBHeader'
import Reveal from '../../components/landing/Reveal'
import Hero from './Hero'
import KpiBand from './KpiBand'
import ProcessFlow from './ProcessFlow'
import Showcase from './Showcase'
import DataSection from './DataSection'
import CtaBand from './CtaBand'
import SiteFooter from './SiteFooter'
import { SHOWCASES } from './landingData'
import heroShot from '../../assets/landing/hero-preview.png'
import exploreShot from '../../assets/landing/report-preview.png'
import styles from './Landing.module.css'

/**
 * 랜딩 화면 (이슈 #101).
 *
 * 섹션을 컴포넌트로 나눈 이유는 길이가 아니라 **성격이 다르기 때문**이다 — 히어로는 전환,
 * 지표는 신뢰, 흐름은 설명, 쇼케이스는 증명, CTA는 마무리를 맡는다. 한 파일에 두면
 * 어느 하나를 손볼 때 나머지를 다 읽어야 한다.
 *
 * 문구·수치는 `landingData.ts` 한 곳에 근거와 함께 모아 둔다.
 */
export default function Landing() {
  return (
    <div className={styles.page}>
      <GNBHeader />
      <Hero />
      <KpiBand />

      <section className={styles.section} id="service">
        <Reveal className={styles.head}>
          <span className={`t-label ${styles.eyebrow}`}>어떻게 계산하는가</span>
          <h2 className={styles.title}>진단에서 판정까지, 네 단계</h2>
          <p className={styles.sub}>
            수치는 결정적 계산이 만들고, AI는 무엇을 계산할지 계획하고 결과를 설명·반박합니다.
          </p>
        </Reveal>
        <ProcessFlow />
      </section>

      <section className={`${styles.section} ${styles.showcases}`}>
        <Showcase
          {...SHOWCASES[0]}
          shot={heroShot}
          width={1440}
          height={1333}
        />
        <Showcase
          {...SHOWCASES[1]}
          shot={exploreShot}
          width={1440}
          height={1043}
          reverse
        />
      </section>

      <DataSection />

      <CtaBand />
      <SiteFooter />
    </div>
  )
}
