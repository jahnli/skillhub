import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '@/api/client'
import { getCasdoorSignInUrl } from './casdoor-session'

vi.mock('@/api/client', () => ({
  authApi: { getProviders: vi.fn(), getMe: vi.fn() },
}))

vi.mock('@/shared/lib/base-path', async (importOriginal) => {
  const { toRouterPath } = await importOriginal<typeof import('@/shared/lib/base-path')>()
  return {
    toRouterPath: (pathname: string, search?: string, hash?: string) =>
      toRouterPath(pathname, search, hash, '/skills/'),
    withBasePath: (pathname: string) => '/skills' + pathname,
  }
})

describe('Casdoor entry', () => {
  beforeEach(() => {
    const location = new URL('https://example.com/skills/space/demo?tab=files#readme')
    vi.stubGlobal('window', {
      location,
      history: { replaceState: (_state: unknown, _title: string, href: string) => { location.href = href } },
    })
    vi.mocked(authApi.getProviders).mockResolvedValue([
      { id: 'oidc', name: 'Casdoor', authorizationUrl: '/oauth2/authorization/oidc', silentSignIn: true },
    ])
    vi.mocked(authApi.getMe).mockResolvedValue(null)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('checks anonymous sessions on each reload and consumes the return marker once', async () => {
    const originalPage = window.location.href
    for (let reload = 0; reload < 2; reload++) {
      const href = await getCasdoorSignInUrl()
      const entry = new URL(href!, originalPage)
      expect(entry.pathname).toBe('/skills/oauth2/authorization/oidc')
      expect(entry.searchParams.get('silent')).toBe('true')
      window.location.href = window.location.origin + '/skills' + entry.searchParams.get('returnTo')
      expect(await getCasdoorSignInUrl()).toBeUndefined()
      expect(window.location.href).toBe(originalPage)
    }
    expect(authApi.getMe).toHaveBeenCalledTimes(2)
  })

  it('resumes checks on the next reload after explicit logout', async () => {
    window.location.search = '?signedOut=1&tab=files'
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(window.location.search).toBe('?tab=files')
    expect(authApi.getProviders).not.toHaveBeenCalled()
    expect(await getCasdoorSignInUrl()).toBeDefined()
  })

  it.each([
    '/api/v1/auth/login/callback?code=example&state=example', '/api/v1/auth/logout/callback',
    '/login/oauth2/code/oidc?code=example&state=example',
    '/oauth2/authorization/oidc', '/login?reason=accountDisabled', '/access-denied',
    '/pending-approval', '/?signedOut=1', '/device', '/cli/auth',
  ])('skips automatic authorization on %s', async (path) => {
    window.location.href = 'https://example.com/skills' + path
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).not.toHaveBeenCalled()
  })

  it.each(['oidc', 'local', 'github', undefined])('preserves an existing %s session', async (oauthProvider) => {
    vi.mocked(authApi.getMe).mockResolvedValue({ userId: 'employee', displayName: 'Employee', platformRoles: [], oauthProvider })
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).not.toHaveBeenCalled()
  })

  it('skips silent sign-in when the session check fails', async () => {
    vi.mocked(authApi.getMe).mockRejectedValue(new Error('Session check failed'))
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).not.toHaveBeenCalled()
  })

  it('leaves deployments without Casdoor on their normal entry path', async () => {
    vi.mocked(authApi.getProviders).mockResolvedValue([])
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).toHaveBeenCalledOnce()
  })
})
