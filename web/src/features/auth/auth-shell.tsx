import type React from 'react'
import { useTranslation } from 'react-i18next'
import { useSitePresentation } from '@/shared/hooks/use-site-presentation'
import { BrandMark } from '@/shared/components/brand-mark'
import { cn } from '@/shared/lib/utils'
import { AuthAssetArtwork } from './auth-asset-artwork'
import styles from './auth-shell.module.css'

interface AuthShellProps {
  children: React.ReactNode
  className?: string
}

/** Authentication layout with the workspace presentation. */
export function AuthShell({ children, className }: AuthShellProps) {
  const { t } = useTranslation()
  const { settings } = useSitePresentation()
  const siteName = settings.siteName ?? 'SkillHub'

  return (
    <section className={cn('relative w-full', className)}>
      <div className={cn('grid min-h-svh w-full bg-background', styles.workspace)}>
        <aside className={styles.brand}>
          <div className={styles.logo}>
            <BrandMark className="h-8 w-8 shrink-0" alt="" />
            <span className="min-w-0 break-words text-base font-normal tracking-tight sm:text-lg">{siteName}</span>
          </div>
          <div className={styles.hero}>
            <div className={styles.copy}>
              <h2 className={styles.title}>
                {t('authShell.heroTitle').split('\n').map((line, index) => (
                  <span key={index} className={index > 0 ? styles.titleContinuation : undefined}>{line}</span>
                ))}
              </h2>
            </div>
            <AuthAssetArtwork className={styles.artwork} />
          </div>
        </aside>

        <div className={styles.main}>
          <div className={styles.accent} aria-hidden="true" />
          <div className="w-full max-w-[400px]">
            {children}
          </div>
        </div>
      </div>
    </section>
  )
}
