import { authApi } from '@/api/client'
import { toRouterPath, withBasePath } from '@/shared/lib/base-path'

const skipKey = 'skillhub:casdoor:skip-once:' + withBasePath('/')

export function skipCasdoorSignInOnce() {
  try {
    window.sessionStorage.setItem(skipKey, String(Date.now() + 5 * 60_000))
  } catch {
    // Automatic sign-in is skipped when storage is unavailable.
  }
}

export async function getCasdoorSignInUrl(): Promise<string | undefined> {
  const { pathname, search, hash } = window.location
  const route = toRouterPath(pathname)
  try {
    const skipUntil = Number(window.sessionStorage.getItem(skipKey))
    window.sessionStorage.removeItem(skipKey)
    if (skipUntil > Date.now() || new URLSearchParams(search).has('signedOut')) return
    if (/^\/(?:device|cli|register|reset-password)(?:\/|$)/.test(route)) return
    const options = { timeoutMs: 2_000 }
    const providers = await authApi.getProviders(undefined, options)
    const casdoor = providers.find(({ silentSignIn }) => silentSignIn)
    if (!casdoor || await authApi.getMe(options)) return

    const returnTo = toRouterPath(pathname, search, hash)

    window.sessionStorage.setItem(skipKey, String(Date.now() + 5 * 60_000))
    const params = new URLSearchParams({ silent: 'true', returnTo })
    return withBasePath(casdoor.authorizationUrl + '?' + params)
  } catch {
    // Anonymous pages and manual sign-in remain usable when the probe fails.
    return undefined
  }
}
