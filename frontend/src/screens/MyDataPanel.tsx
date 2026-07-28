import { Landmark, CreditCard, HandCoins, BadgeDollarSign } from 'lucide-react'
import Button from '../components/Button'
import styles from './MyDataPanel.module.css'

// 데모 연출 — 실제 마이데이터 연동은 대학생 개발 범위 밖이라, "불러오기"는 폼을 데모값으로 채운다.
// 아이콘 색은 기존 팔레트 유지: 계좌 초록 · 카드 파랑 · 대출 주황 · 소득 보라.
const TOKENS = [
  { label: '계좌', Icon: Landmark, color: 'var(--green-500)' },
  { label: '카드', Icon: CreditCard, color: 'var(--blue-500)' },
  { label: '대출', Icon: HandCoins, color: 'var(--orange-500)' },
  { label: '소득', Icon: BadgeDollarSign, color: 'var(--purple-500)' },
]

/** KB 마이데이터 연동 패널(데모) — "불러오기" 클릭 시 onImport로 데모 프로필을 폼에 채운다. */
export default function MyDataPanel({ onImport }: { onImport: () => void }) {
  return (
    <section className={styles.panel}>
      <div className={styles.header}>
        <span className={`t-body-strong ${styles.title}`}>마이데이터로 간편하게 불러오기 (선택)</span>
        {/*
          배지가 "KB 마이데이터 공식 연동"이었다. 실체는 데모 프로필로 폼을 채우는 연출인데
          주최사 이름을 걸고 사실을 단정하는 표기라, 심사위원이 눌러 보면 그 자리에서 반증된다.
          하지 않은 일을 했다고 말하지 않는다 — 데모임을 배지 자체에 밝힌다.
        */}
        <span className={`t-caption ${styles.badge}`}>마이데이터 연동 (데모)</span>
      </div>
      <p className={`t-caption ${styles.desc}`}>
        마이데이터로 자산·소득 정보를 불러와 자금 진단을 더 정확하게 진행하는 흐름입니다. 이
        프로토타입에서는 실제 연동 대신 데모 프로필 값으로 채워집니다.
      </p>
      <div className={styles.action}>
        <div className={styles.tokens}>
          {TOKENS.map(({ label, Icon, color }) => (
            <span key={label} className={styles.token}>
              <Icon size={18} color={color} strokeWidth={2} aria-hidden />
              <span className={`t-body ${styles.tokenLabel}`}>{label}</span>
            </span>
          ))}
        </div>
        <Button variant="primary" size="lg" onClick={onImport} className={styles.importBtn}>
          마이데이터 불러오기
        </Button>
      </div>
      <p className={`t-caption ${styles.footer}`}>
        ⓘ 직접 입력도 가능해요. 마이데이터로 불러온 항목은 수정할 수 있습니다.
      </p>
    </section>
  )
}
