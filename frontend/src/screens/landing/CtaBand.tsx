import { useNavigate } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
import Reveal from '../../components/landing/Reveal'
import styles from './CtaBand.module.css'

/** 마지막 전환 지점. 스크롤을 끝까지 내린 사람에게 남는 행동은 하나여야 한다. */
export default function CtaBand() {
  const navigate = useNavigate()

  return (
    <section className={styles.wrap}>
      <Reveal className={styles.band}>
        <div className={styles.grid} aria-hidden />
        <div className={styles.content}>
          <h2 className={styles.title}>
            내 예산으로 가능한 입지를
            <br />
            지금 바로 확인해 보세요.
          </h2>
          <p className={styles.sub}>
            자기자본과 월 상환 여력만 입력하면 3분 안에 도달 가능한 상권이 나옵니다.
          </p>
          <button type="button" className={styles.cta} onClick={() => navigate('/diagnose')}>
            AI 입지 진단 시작
            <ArrowRight size={17} aria-hidden className={styles.arrow} />
          </button>
          <p className={`t-caption ${styles.note}`}>
            공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다.
          </p>
        </div>
      </Reveal>
    </section>
  )
}
