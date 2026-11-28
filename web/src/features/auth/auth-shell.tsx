import type React from 'react'
import { useTranslation } from 'react-i18next'
import { useSitePresentation } from '@/shared/hooks/use-site-presentation'
import { BrandMark } from '@/shared/components/brand-mark'
import { withBasePath } from '@/shared/lib/base-path'
import { cn } from '@/shared/lib/utils'

interface AuthShellProps {
  children: React.ReactNode
  className?: string
}

/** Shared theme-aware product introduction and form layout for login and registration. */
export function AuthShell({ children, className }: AuthShellProps) {
  const { t } = useTranslation()
  const { settings } = useSitePresentation()
  const siteName = settings.siteName ?? 'SkillHub'

  return (
    <section className={cn('relative w-full', className)}>
      <div className="grid min-h-svh w-full bg-background xl:grid-cols-[53fr_47fr]">
        <aside className="sticky top-0 hidden h-svh overflow-hidden border-r border-border bg-muted text-foreground xl:block">
          <div className="relative z-10 px-12 pt-28 2xl:px-16">
            <div className="flex items-center gap-3">
              <BrandMark className="h-8 w-8" alt="" />
              <span className="min-w-0 break-words text-lg font-semibold tracking-tight">{siteName}</span>
            </div>
            <h2 className="mt-7 break-words text-[clamp(4.5rem,6.5vw,6rem)] font-black leading-none tracking-[-0.04em]">{siteName}</h2>
            <p className="mt-5 max-w-[420px] whitespace-pre-line text-2xl font-semibold leading-snug tracking-tight">{t('authShell.heroTitle')}</p>
            <p className="mt-4 max-w-[350px] whitespace-pre-line text-sm leading-6 text-muted-foreground">{t('authShell.heroSubtitle')}</p>
          </div>

          <div className="pointer-events-none absolute bottom-[3%] -right-[3%] h-[60%] w-[78%]" aria-hidden="true">
            <img src={withBasePath('/login-skill-art-light.png')} alt="" width="1484" height="1060" className="h-full w-full object-contain dark:hidden" />
            <img src={withBasePath('/login-skill-art-dark.png')} alt="" width="1484" height="1060" className="hidden h-full w-full object-contain dark:block" />
          </div>
        </aside>

        <div className="flex min-h-svh items-start justify-center px-5 pb-8 pt-20 sm:px-10 xl:px-12 xl:py-24">
          <div className="w-full max-w-[500px]">
            {children}
          </div>
        </div>
      </div>
    </section>
  )
}
