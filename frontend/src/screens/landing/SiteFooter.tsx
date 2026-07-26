import logo from '../../assets/brand/ventry-logo.png'
import { GITHUB_URL } from './landingData'
import styles from './SiteFooter.module.css'

/** 랜딩 푸터 — 링크는 실제로 도달하는 곳만 둔다(존재하지 않는 약관 페이지는 만들지 않는다). */
export default function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <div className={styles.brand}>
          <img className={styles.logo} src={logo} alt="Ventry" width={296} height={78} />
          <span className={`t-caption ${styles.tag}`}>내 한도로 가능한 최적의 입지</span>
        </div>

        <nav className={styles.nav} aria-label="사이트 링크">
          <a className={`t-body ${styles.link}`} href="#service">
            서비스 소개
          </a>
          <a className={`t-body ${styles.link}`} href="#sources">
            데이터 출처
          </a>
          <a
            className={`t-body ${styles.link}`}
            href={GITHUB_URL}
            target="_blank"
            rel="noreferrer noopener"
          >
            GitHub ↗
          </a>
          <a className={`t-body ${styles.link}`} href={`${GITHUB_URL}/issues`} target="_blank" rel="noreferrer noopener">
            문의하기 ↗
          </a>
        </nav>

        <div className={styles.legal}>
          <p className={`t-caption ${styles.notice}`}>
            본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 자격 요건 부합
            상품을 확인한 결과이며, 한도·승인은 기관 심사 사항입니다. 임대료는 한국부동산원 상권
            분기 평균(추정), 권리금은 연간 조사(전년 기준)입니다.
          </p>
          <p className={`t-caption ${styles.copy}`}>
            KB 제8회 Future Finance A.I. Challenge 출품작 · © 2026 Ventry
          </p>
        </div>
      </div>
    </footer>
  )
}
