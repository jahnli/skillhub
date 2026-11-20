import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '@/api/client'
import { getCasdoorSignInUrl, skipCasdoorSignInOnce } from './casdoor-session'

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
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('preserves a deep link and skips the next page load after the Casdoor round trip', async () => {
    const href = await getCasdoorSignInUrl()
    const url = new URL(href!, window.location.origin)
    expect(url.pathname).toBe('/skills/oauth2/authorization/oidc')
    expect(url.searchParams.get('silent')).toBe('true')
    expect(url.searchParams.get('returnTo')).toBe('/space/demo?tab=files#readme')
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).toHaveBeenCalledTimes(1)
  })

  it('skips the automatic login on an explicit logout', async () => {
    skipCasdoorSignInOnce()
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getProviders).not.toHaveBeenCalled()
  })

  it('leaves deployments without Casdoor on their normal entry path', async () => {
    vi.mocked(authApi.getProviders).mockResolvedValue([])
    expect(await getCasdoorSignInUrl()).toBeUndefined()
    expect(authApi.getMe).not.toHaveBeenCalled()
  })
})
