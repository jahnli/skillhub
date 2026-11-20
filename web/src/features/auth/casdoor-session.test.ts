import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '@/api/client'
import { getCasdoorSignInUrl, pauseCasdoorSignIn } from './casdoor-session'

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
    const storage = new Map<string, string>()
    vi.stubGlobal('window', {
      location: new URL('https://example.com/skills/space/demo?tab=files#readme'),
      sessionStorage: {
        getItem: (key: string) => storage.get(key),
        setItem: (key: string, value: string) => storage.set(key, value),
        removeItem: (key: string) => storage.delete(key),
      },
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

  it('preserves a deep link and suppresses repeated navigation until the cooldown expires', async () => {
    vi.useFakeTimers()
    const href = await getCasdoorSignInUrl()
    const url = new URL(href!, window.location.origin)
    expect(url.pathname).toBe('/skills/oauth2/authorization/oidc')
    expect(url.searchParams.get('silent')).toBe('true')
    expect(url.searchParams.get('returnTo')).toBe('/space/demo?tab=files#readme')
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    window.location.pathname = '/skills/terms'
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(5 * 60_000)
    expect(await getCasdoorSignInUrl()).toBeDefined()
  })

  it('skips the automatic login on an explicit logout', async () => {
    pauseCasdoorSignIn()
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).not.toHaveBeenCalled()
  })

  it.each([
    '/login/oauth2/code/oidc?code=example&state=example',
    '/oauth2/authorization/oidc', '/login?reason=accountDisabled', '/access-denied',
    '/pending-approval', '/?signedOut=1', '/device', '/cli/auth',
  ])('skips automatic authorization on %s', async (path) => {
    window.location.href = 'https://example.com/skills' + path
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).not.toHaveBeenCalled()
  })

  it('keeps an existing SkillHub session on its original page', async () => {
    vi.mocked(authApi.getMe).mockResolvedValue({ userId: 'employee', displayName: 'Employee', platformRoles: [] })
    expect(await getCasdoorSignInUrl()).toBeUndefined()
  })

  it('leaves deployments without Casdoor on their normal entry path', async () => {
    vi.mocked(authApi.getProviders).mockResolvedValue([])
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getMe).not.toHaveBeenCalled()
  })
})
