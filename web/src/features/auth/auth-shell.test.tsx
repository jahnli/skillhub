vi.mock('@/shared/hooks/use-site-presentation', async (original) => {
  const actual = await original<typeof import('@/shared/hooks/use-site-presentation')>()
  return { ...actual, useSitePresentation: () => ({ settings: actual.defaultSitePresentation() }) }
})
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
vi.mock('react-i18next', async (original) => ({ ...await original<typeof import('react-i18next')>(), useTranslation: () => ({ t: (key: string) => key }) }))

import { AuthShell } from './auth-shell'

describe('AuthShell', () => {
  it('renders the workspace branding and form', () => {
    const html = renderToStaticMarkup(<AuthShell><div>Login form</div></AuthShell>)

    expect(html).toContain('SkillHub')
    expect(html).toContain('max-w-[400px]')
    expect(html).not.toContain('authShell.shortTagline')
    expect(html).not.toContain('authShell.backHome')
    expect(html).toContain('Login form')
  })
})
