import { useNavigate } from 'react-router-dom'
import { ArrowRight, Sparkles } from 'lucide-react'
import BrowserMock from '../../components/landing/BrowserMock'
import Reveal from '../../components/landing/Reveal'
import heroShot from '../../assets/landing/hero-preview.png'
import { HERO_OVERLAY } from './landingData'
import styles from './Hero.module.css'

/**
 * 히어로 — 좌 카피 / 우 브라우저 목업 + 수치 오버레이.
 *
 * 배경의 격자는 3% 불투명도다. 그 이상 올리면 헤드라인의 대비가 떨어지고, 빼면 히어로가
 * 흰 여백처럼 비어 보인다. 지도 화면을 다루는 서비스라 격자 자체가 맥락에 맞는다.
 */
export default function Hero() {
  const navigate = useNavigate()

  return (
    <section className={styles.hero}>
      <div className={styles.grid} aria-hidden />
      <div className={styles.glow} aria-hidden />

      <div className={styles.inner}>
        <Reveal className={styles.copy}>
          <span className={`t-label ${styles.badge}`}>
            <Sparkles size={13} aria-hidden />
            여유 자금 우선 입지 컨설팅
          </span>

          <h1 className={styles.headline}>
            어디가 좋은가가 아니라,
            <br />
            <em className={styles.accent}>내 한도로</em> 어디까지 가능한가.
          </h1>

          <p className={styles.sub}>
            정책자금·보증·대출을 함께 계산해 실제 예산 범위를 만들고, 그 예산으로 도달 가능한 서울
            상권만 남깁니다. 모든 수치는 공개 자료 기반 결정적 계산 결과입니다.
          </p>

          <div className={styles.ctaRow}>
            <button
              type="button"
              className={`${styles.cta} ${styles.ctaPrimary}`}
              onClick={() => navigate('/diagnose?demo=1')}
            >
              AI 입지 진단 시작하기
              <ArrowRight size={17} aria-hidden className={styles.ctaArrow} />
            </button>
          </div>

          <p className={`t-caption ${styles.note}`}>
            데모 프로필 — 예비창업자 · 만 32세 · 자기자본 5,000만원 · 마포 카페
          </p>
        </Reveal>

        <Reveal className={styles.stage} delay={120}>
          <BrowserMock
            src={heroShot}
            alt="입지 추천 화면 — 지도의 판정 마커와 추천 상권 목록, 상권별 근거 패널"
            width={1440}
            height={1333}
            url="ventry.app/map"
          />

          {/* 캡처에 실제로 찍힌 값만 얹는다 — 오버레이가 캡처와 다른 숫자를 말하면 안 된다. */}
          <div className={styles.overlay} aria-hidden>
            {HERO_OVERLAY.map(({ label, value }) => (
              <div key={label} className={styles.stat}>
                <span className={`t-caption ${styles.statLabel}`}>{label}</span>
                <span className={styles.statValue}>{value}</span>
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  )
}
