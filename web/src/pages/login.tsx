import { useNavigate, useSearch } from '@tanstack/react-router'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Building2, MoreHorizontal, LogIn, Eye, EyeOff } from 'lucide-react'
import { getSessionBootstrapRuntimeConfig } from '@/api/client'
import { AuthShell } from '@/features/auth/auth-shell'
import { AuthMethodButtonList } from '@/features/auth/login-button'
import { SessionBootstrapEntry } from '@/features/auth/session-bootstrap-entry'
import { useAuthMethods } from '@/features/auth/use-auth-methods'
import { useLocalAuthCapabilities } from '@/features/auth/use-local-auth-capabilities'
import { usePasswordLogin } from '@/features/auth/use-password-login'
import { Button, buttonVariants } from '@/shared/ui/button'
import { withBasePath } from '@/shared/lib/base-path'
import styles from '@/features/auth/login-actions.module.css'
import { Input } from '@/shared/ui/input'
import { resolveAuthReturnTo } from '@/shared/lib/auth-route'

/**
 * Authentication entry page.
 *
 * It combines password login, OAuth entry points, and optional session-bootstrap support while
 * preserving the route the user originally intended to visit.
 */
export function LoginPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const search = useSearch({ from: '/login' })
  const loginMutation = usePasswordLogin()
  const bootstrapConfig = getSessionBootstrapRuntimeConfig()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [showAlternatives, setShowAlternatives] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<{ username?: string, password?: string }>({})
  const returnTo = resolveAuthReturnTo(search.returnTo)
  const {
    data: authMethods, isLoading: authMethodsLoading, isError: authMethodsError,
    isFetching: authMethodsFetching, refetch: refetchAuthMethods,
  } = useAuthMethods(returnTo)
  const {
    data: localCapabilities, isLoading: localCapabilitiesLoading, isError: localCapabilitiesError,
    isFetching: localCapabilitiesFetching, refetch: refetchLocalCapabilities,
  } = useLocalAuthCapabilities()
  const passwordEnabled = localCapabilities?.passwordLoginEnabled
    ?? (localCapabilitiesError && !authMethodsError && authMethods?.some((method) => method.methodType === 'PASSWORD') === true)
  const disabledMessage = search.reason === 'accountDisabled' ? t('error.auth.local.accountDisabled') : null
  const bootstrapMethod = authMethods?.find((method) => method.methodType === 'SESSION_BOOTSTRAP')
  const externalMethods = (authMethods ?? []).filter((method) => method.methodType === 'OAUTH_REDIRECT')
  const primaryOAuth = bootstrapConfig.enabled ? undefined : externalMethods.find((method) =>
    ['oidc', 'casdoor', 'enterprise-sso'].includes(method.provider.toLowerCase()),
  )
  const otherProviders = externalMethods.filter((method) => method !== primaryOAuth)
  const hasOrganizationMethod = bootstrapConfig.enabled || !!primaryOAuth
  const hasExternalMethods = externalMethods.length > 0
  const canSwitch = hasOrganizationMethod && (!!passwordEnabled || otherProviders.length > 0)
  const showGroup = hasOrganizationMethod && !(canSwitch && showAlternatives)
  const noLoginMethod = !passwordEnabled && !hasOrganizationMethod && !hasExternalMethods
  const configUnavailable = noLoginMethod && (localCapabilitiesError || authMethodsError)
    && !localCapabilitiesLoading && !authMethodsLoading

  function retryLoginConfig() {
    if (localCapabilitiesError) void refetchLocalCapabilities()
    if (authMethodsError) void refetchAuthMethods()
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmedUsername = username.trim()
    const nextFieldErrors: { username?: string, password?: string } = {}

    if (!trimmedUsername) {
      nextFieldErrors.username = t('login.usernameRequired')
    }
    if (!password) {
      nextFieldErrors.password = t('login.passwordRequired')
    }
    if (nextFieldErrors.username || nextFieldErrors.password) {
      setFieldErrors(nextFieldErrors)
      return
    }

    setFieldErrors({})
    try {
      await loginMutation.mutateAsync({ username: trimmedUsername, password })
      await navigate({ to: returnTo })
    } catch {
      // mutation state drives the error UI
    }
  }

  return (
    <AuthShell>
      <div className="flex flex-col gap-6">
        <div className="space-y-3">
          <h1 className="text-[34px] font-normal leading-tight tracking-tight text-foreground sm:text-[38px]">{t('login.eyebrow')}</h1>
          <p className="text-lg leading-relaxed text-muted-foreground">
            {t('login.title')}
          </p>
        </div>

        {disabledMessage ? (
          <div className="rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            {disabledMessage}
          </div>
        ) : null}

        {bootstrapConfig.enabled && (
          <div hidden={!showGroup}>
            <SessionBootstrapEntry
              methodDisplayName={bootstrapMethod?.displayName}
              onAuthenticated={() => navigate({ to: returnTo })}
              compact
            />
          </div>
        )}
        {primaryOAuth && showGroup && (
          <a data-analytics-id="auth.oauth.request" href={withBasePath(primaryOAuth.actionUrl)}
            className={buttonVariants({ className: styles.groupButton })}>
            <Building2 aria-hidden="true" size={17} />
            {t('login.groupAccount')}
          </a>
        )}

        {passwordEnabled && !showGroup && !authMethodsLoading ? <div>
        <form data-analytics-id="auth.login.submit" className="space-y-4" onSubmit={handleSubmit}>
              <div className="space-y-2">
                <label className="text-sm font-medium" htmlFor="username">{t('login.username')}</label>
                  <Input
                    id="username"
                    autoComplete="username"
                    value={username}
                    onChange={(event) => {
                      setUsername(event.target.value)
                      if (fieldErrors.username) {
                        setFieldErrors((current) => ({ ...current, username: undefined }))
                      }
                    }}
                    placeholder={t('login.usernamePlaceholder')}
                    aria-invalid={fieldErrors.username ? 'true' : 'false'}
                  />
                {fieldErrors.username ? (
                  <p className="text-sm text-destructive">{fieldErrors.username}</p>
                ) : null}
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium" htmlFor="password">{t('login.password')}</label>
                <div className="relative">
                  <Input
                    id="password"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    value={password}
                    onChange={(event) => {
                      setPassword(event.target.value)
                      if (fieldErrors.password) {
                        setFieldErrors((current) => ({ ...current, password: undefined }))
                      }
                    }}
                    placeholder={t('login.passwordPlaceholder')}
                    className="pr-10"
                    aria-invalid={fieldErrors.password ? 'true' : 'false'}
                  />
                  <button data-analytics-id="auth.password.visibility"
                    type="button"
                    aria-label={showPassword ? t('login.hidePassword') : t('login.showPassword')}
                    aria-pressed={showPassword}
                    onClick={() => setShowPassword((current) => !current)}
                    className="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-[var(--control-radius)] text-muted-foreground hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                {fieldErrors.password ? (
                  <p className="text-sm text-destructive">{fieldErrors.password}</p>
                ) : null}
              </div>
              {loginMutation.error ? (
                <p className="text-sm text-destructive">{loginMutation.error.message}</p>
              ) : null}
              <Button className="w-full" disabled={loginMutation.isPending} type="submit">
                <LogIn aria-hidden="true" className="h-4 w-4" />
                {loginMutation.isPending ? t('login.submitting') : t('login.submit')}
              </Button>
        </form>
        </div> : null}

        {configUnavailable ? (
          <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-muted/40 p-3">
            <p role="alert" className="text-sm text-foreground">{t('login.configUnavailable')}</p>
            <Button type="button" variant="outline" size="sm" disabled={authMethodsFetching || localCapabilitiesFetching}
              onClick={retryLoginConfig}>
              {t('login.retryConfig')}
            </Button>
          </div>
        ) : null}
        {localCapabilities && noLoginMethod && !configUnavailable && !authMethodsLoading ? (
          <p className="text-sm text-muted-foreground">{t('systemConfig.passwordDisabled')}</p>
        ) : null}

        {!showGroup && (authMethodsLoading || otherProviders.length > 0) && (
          <section className="space-y-3" aria-label={t('login.otherMethods')}>
            {passwordEnabled && <div className={styles.divider}>{t('login.or')}</div>}
            <AuthMethodButtonList methods={otherProviders} isLoading={authMethodsLoading} compact layout="stack" />
          </section>
        )}

        {canSwitch && (
          <div className="space-y-3">
            <div className={styles.divider}>{t('login.or')}</div>
            <Button type="button" variant="outline" className={styles.switchButton}
              data-analytics-id={showGroup ? 'auth.personal.select' : 'auth.enterprise.select'}
              onClick={() => setShowAlternatives(showGroup)}>
              {showGroup ? <MoreHorizontal aria-hidden="true" size={16} /> : <Building2 aria-hidden="true" size={16} />}
              {t(showGroup ? 'login.useOtherMethods' : 'login.groupAccount')}
            </Button>
          </div>
        )}
      </div>
    </AuthShell>
  )
}
