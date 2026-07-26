import logo from '../../assets/brand/ventry-logo.png'
import styles from './GNBHeader.module.css'

/**
 * 상단 GNB — 로고+Beta(좌) / 서비스 소개·데이터 출처(우). 전 화면 공용.
 *
 * 로그인 버튼과 이용 안내 링크는 두지 않는다 (이슈 #108). 인증·회원 개념이 백엔드에 없고
 * (계약 6개 엔드포인트 어디에도 인증이 없다), 스펙 §0-2 가 결과 저장·공유를 P2 로 미뤄 둔
 * 상태라 저장할 것이 없는데 로그인할 이유가 없다. 눌러도 아무 일이 없는 버튼을 화면 최상단에
 * 두는 쪽이 더 큰 위험이다.
 */
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
