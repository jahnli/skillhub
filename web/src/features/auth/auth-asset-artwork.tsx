import { useEffect, useRef, useState } from 'react'
import { Blocks, Bot, GitBranch, ListChecks, Cable } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import styles from './auth-asset-artwork.module.css'

const assetTypes = [
  { key: 'skills', Icon: Blocks },
  { key: 'rules', Icon: ListChecks },
  { key: 'mcp', Icon: Cable },
  { key: 'workflow', Icon: GitBranch },
  { key: 'agent', Icon: Bot },
] as const

export function AuthAssetArtwork({ className }: { className?: string }) {
  const { t } = useTranslation()
  const artworkRef = useRef<HTMLDivElement>(null)

  const [rotation, setRotation] = useState({ active: 0, previous: -1, direction: 1 })

  useEffect(() => {
    const artwork = artworkRef.current
    if (!artwork) return
    const media = window.matchMedia(
      '(max-width: 1023px), (prefers-reduced-motion: reduce)'
    )
    let timer: ReturnType<typeof setTimeout> | undefined

    const drift = () => {
      clearTimeout(timer)
      if (media.matches || document.hidden) return
      const x = Math.random() * 80 - 40
      const y = Math.random() * 72 - 48
      const duration = 1400 + Math.random() * 1000
      artwork.style.setProperty('--orb-x', `${x}px`)
      artwork.style.setProperty('--orb-y', `${y}px`)
      artwork.style.setProperty('--orb-duration', `${duration}ms`)
      artwork.style.setProperty('--orb-shadow-scale', `${0.85 + y / 240}`)
      artwork.style.setProperty('--orb-shadow-opacity', `${0.7 + y / 240}`)
      timer = setTimeout(drift, duration)
    }

    let rotationTimer: ReturnType<typeof setInterval> | undefined
    const syncMotion = () => {
      clearInterval(rotationTimer)
      drift()
      if (media.matches || document.hidden) return
      rotationTimer = setInterval(() => {
        const direction = Math.random() < 0.5 ? -1 : 1
        setRotation(({ active }) => ({ active: (active + 1) % assetTypes.length, previous: active, direction }))
      }, 2000)
    }

    syncMotion()
    media.addEventListener('change', syncMotion)
    document.addEventListener('visibilitychange', syncMotion)
    return () => {
      clearInterval(rotationTimer)
      clearTimeout(timer)
      media.removeEventListener('change', syncMotion)
      document.removeEventListener('visibilitychange', syncMotion)
    }
  }, [])

  return (
    <div ref={artworkRef} className={className}>
      <div className={styles.shadow} aria-hidden="true" />
      <div className={styles.system}>
        <div className={styles.orb}>
          <ul className={styles.assets} data-direction={rotation.direction}>
            {assetTypes.map(({ key, Icon }, index) => (
              <li
                key={key}
                className={styles.asset}
                data-asset={key}
                data-active={index === rotation.active}
                data-entering={rotation.previous >= 0 && index === rotation.active}
                data-leaving={index === rotation.previous}
              >
                <Icon size={40} strokeWidth={1.2} aria-hidden="true" />
                {t(`authShell.assets.${key}`)}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  )
}
