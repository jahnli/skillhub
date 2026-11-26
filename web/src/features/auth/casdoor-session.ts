import { authApi } from '@/api/client'
import { toRouterPath, withBasePath } from '@/shared/lib/base-path'

const checkComplete = 'skillhubSsoChecked'

export async function getCasdoorSignInUrl(): Promise<string | undefined> {
  const page = new URL(window.location.href)
  const route = toRouterPath(page.pathname)
  try {
    // Consume the return marker before React mounts so the next reload checks again.
    if (page.searchParams.get(checkComplete) === '1' || page.searchParams.get('signedOut') === '1') {
      page.searchParams.delete(checkComplete)
      page.searchParams.delete('signedOut')
      window.history.replaceState(window.history.state, '', page.href)
      return
    }
    if (/^\/(?:api|oauth2|login|device|cli|register|reset-password|pending-approval|access-denied)(?:\/|$)/.test(route)) return
    const options = { timeoutMs: 2_000 }
    const user = await authApi.getMe(options)
    if (user) return

    const providers = await authApi.getProviders(undefined, options)
    const casdoor = providers.find(({ silentSignIn }) => silentSignIn)
    if (!casdoor) return

    page.searchParams.set(checkComplete, '1')
    const returnTo = toRouterPath(page.pathname, page.search, page.hash)
    const params = new URLSearchParams({ silent: 'true', returnTo })
    return withBasePath(casdoor.authorizationUrl + '?' + params)
  } catch {
    // Anonymous pages and manual sign-in remain usable when the probe fails.
    return undefined
  }
}
