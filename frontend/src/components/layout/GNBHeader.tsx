import logo from '../../assets/brand/ventry-logo.png'
import styles from './GNBHeader.module.css'

/** 상단 GNB — 로고+Beta(좌) / 이용안내·데이터출처·로그인(우). 전 화면 공용. */
export default function GNBHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.brand}>
        {/*
          로고는 이미지 한 장이다(심볼+워드마크 일체형). 표시 높이만 고정하고 가로는 비율에
          맡긴다 — 로고 비율을 CSS로 강제하면 교체할 때마다 값을 다시 맞춰야 한다.
          원본 크기를 그대로 적어 두면 로딩 중 헤더가 밀리지 않는다.
        */}
        <img className={styles.logo} src={logo} alt="Ventry" width={296} height={78} />
        <span className={`t-caption ${styles.beta}`}>Beta</span>
      </div>
      {/*
        링크는 랜딩의 해당 섹션으로 보낸다 — 전부 `href="#"`이면 심사위원이 눌렀을 때
        아무 일도 일어나지 않는다. 서비스 화면에서 눌러도 랜딩으로 이동해 그 자리로 스크롤된다.
      */}
      <nav className={styles.nav}>
        <a className={`t-body ${styles.link}`} href="/#service">
          서비스 소개
        </a>
        <a className={`t-body ${styles.link}`} href="/#sources">
          데이터 출처
        </a>
      </nav>
    </header>
  )
}
