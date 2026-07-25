import styles from './GNBHeader.module.css'

function Logo() {
  return (
    <span className={styles.logo}>
      <svg width="26" height="26" viewBox="0 0 26 26" fill="none" aria-hidden>
        <path
          d="M4 5 L13 20 L22 5"
          stroke="var(--color-text-primary)"
          strokeWidth="3.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M9.5 5 L13 11 L16.5 5"
          stroke="var(--color-brand-primary)"
          strokeWidth="3.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <span className={`t-title2 ${styles.word}`}>Ventry</span>
    </span>
  )
}

/** 상단 GNB — 로고+Beta(좌) / 이용안내·데이터출처·로그인(우). 전 화면 공용. */
export default function GNBHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.brand}>
        <Logo />
        <span className={`t-caption ${styles.beta}`}>Beta</span>
      </div>
      <nav className={styles.nav}>
        <a className={`t-body ${styles.link}`} href="#">
          이용 안내
        </a>
        <a className={`t-body ${styles.link}`} href="#">
          데이터 출처
        </a>
        <button type="button" className={`t-label ${styles.login}`}>
          로그인
        </button>
      </nav>
    </header>
  )
}
