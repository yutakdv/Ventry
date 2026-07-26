import { Check } from 'lucide-react'
import BrowserMock from '../../components/landing/BrowserMock'
import Reveal from '../../components/landing/Reveal'
import styles from './Showcase.module.css'

/**
 * 기능 소개 — 좌우 번갈아 배치.
 *
 * 두 블록을 같은 방향으로 쌓으면 스크롤이 단조로워지고, 번갈아 두면 눈이 좌우로 움직이며
 * 각 블록이 별개의 이야기로 읽힌다. `reverse`는 그 뒤집기 하나만 담당한다.
 */
export default function Showcase({
  eyebrow,
  title,
  desc,
  points,
  shot,
  shotUrl,
  alt,
  width,
  height,
  reverse = false,
}: {
  eyebrow: string
  /** 줄바꿈은 `\n`으로 넣는다 — 소개면의 제목은 끊는 위치가 곧 리듬이다. */
  title: string
  desc: string
  points: string[]
  shot: string
  shotUrl: string
  alt: string
  width: number
  height: number
  reverse?: boolean
}) {
  return (
    <div className={`${styles.row} ${reverse ? styles.reverse : ''}`}>
      <Reveal className={styles.copy}>
        <span className={`t-label ${styles.eyebrow}`}>{eyebrow}</span>
        <h3 className={styles.title}>
          {title.split('\n').map((line, i) => (
            <span key={line} className={styles.titleLine}>
              {i > 0 && <br />}
              {line}
            </span>
          ))}
        </h3>
        <p className={styles.desc}>{desc}</p>
        <ul className={styles.points}>
          {points.map((p) => (
            <li key={p} className={`t-body ${styles.point}`}>
              <span className={styles.check}>
                <Check size={12} aria-hidden />
              </span>
              {p}
            </li>
          ))}
        </ul>
      </Reveal>

      <Reveal className={styles.stage} delay={100}>
        <BrowserMock src={shot} alt={alt} width={width} height={height} url={shotUrl} />
      </Reveal>
    </div>
  )
}
