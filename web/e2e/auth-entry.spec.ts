import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { createFreshSession } from './helpers/session'

test.describe('Auth Entry', () => {
  test.beforeEach(async ({ page }) => {
    await setEnglishLocale(page)
  })

  test('validates login fields without offering registration', async ({ page }) => {
    await page.route('**/runtime-config.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: 'window.__SKILLHUB_RUNTIME_CONFIG__ = { authDirectEnabled: "true", authDirectProvider: "local" }',
      })
    })
    await page.goto('/login?returnTo=%2Fdashboard%2Ftokens')

    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
    await expect(page.getByText(/password compatibility layer/i)).toHaveCount(0)

    await page.getByRole('button', { name: 'Login' }).click()
    await expect(page.getByText('Username is required')).toBeVisible()
    await expect(page.getByText('Password is required')).toBeVisible()

    await expect(page.getByRole('link', { name: 'Sign up now' })).toHaveCount(0)
  })

  test('shows configured OAuth methods without exposing unsupported organization discovery', async ({ page }) => {
    await page.route('**/api/v1/auth/methods*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          msg: 'ok',
          data: [
            {
              id: 'github',
              methodType: 'OAUTH_REDIRECT',
              provider: 'github',
              displayName: 'GitHub',
              actionUrl: '/oauth2/authorization/github',
            },
            {
              id: 'enterprise-discovery',
              methodType: 'ENTERPRISE_DISCOVERY',
              provider: 'enterprise',
              displayName: 'Organization login',
              actionUrl: '/api/v1/auth/login-discovery',
            },
          ],
        }),
      })
    })

    await page.goto('/login')

    await expect(page.getByRole('button', { name: 'GitHub' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Organization login' })).toHaveCount(0)
  })

  test('keeps the login form available when the session status check fails', async ({ page }) => {
    await page.route('**/api/v1/auth/me', async (route) => {
      await route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":503,"msg":"Unavailable"}' })
    })

    await page.goto('/login')

    await expect(page.getByLabel('Username')).toBeVisible()
    await expect(page.getByLabel('Password', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Login' })).toBeVisible()

    await page.goto('/dashboard/tokens')
    await expect(page.getByRole('heading', { name: 'Something went wrong' })).toBeVisible()
    await expect(page.getByLabel('Password', { exact: true })).toHaveCount(0)
  })

  test('routes configured direct password login to the direct endpoint', async ({ page }) => {
    let directRequests = 0
    let localRequests = 0
    await page.route('**/runtime-config.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: 'window.__SKILLHUB_RUNTIME_CONFIG__ = { authDirectEnabled: "true", authDirectProvider: "local" }',
      })
    })
    await page.route('**/api/v1/auth/direct/login', async (route) => {
      directRequests += 1
      expect(route.request().method()).toBe('POST')
      expect(route.request().postDataJSON()).toEqual({ provider: 'local', username: 'direct-demo', password: 'Valid1!pass' })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, msg: 'ok', data: { userId: 'direct-demo', username: 'direct-demo', displayName: 'Direct Demo', platformRoles: [] } }),
      })
    })
    await page.route('**/api/v1/auth/local/login', async (route) => {
      localRequests += 1
      await route.abort()
    })

    await page.goto('/login')
    await page.getByLabel('Username').fill('direct-demo')
    await page.getByLabel('Password', { exact: true }).fill('Valid1!pass')
    await page.getByRole('button', { name: 'Login' }).click()

    await expect(page).toHaveURL('/')
    expect(directRequests).toBe(1)
    expect(localRequests).toBe(0)
  })

  test('keeps the desktop layout stable across theme and login-mode changes', async ({ page }, testInfo) => {
    await page.route('**/runtime-config.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: 'window.__SKILLHUB_RUNTIME_CONFIG__ = { authSessionBootstrapEnabled: "true", authSessionBootstrapProvider: "proxy", authSessionBootstrapAuto: "false" }',
      })
    })

    for (const viewport of [{ width: 1280, height: 800 }, { width: 1920, height: 1080 }]) {
      await page.setViewportSize(viewport)
      await page.goto('/login')
      await page.addStyleTag({ content: '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }' })
      const title = page.getByRole('heading', { name: 'Welcome back' })
      const registerLink = page.getByRole('link', { name: 'Sign up now' })
      await expect(title).toBeVisible()
      await expect(registerLink).toHaveCount(0)

      const before = await title.boundingBox()
      const switchMethod = page.getByRole('button', { name: 'Sign in with other methods' })
      await expect(page.getByRole('button', { name: 'Sign in with group account' })).toBeVisible()
      await switchMethod.click()
      await expect(page.getByLabel('Password', { exact: true })).toBeVisible()
      await expect(registerLink).toHaveCount(0)
      await page.getByRole('button', { name: 'Sign in with group account' }).click()
      await expect(switchMethod).toBeVisible()
      const after = await title.boundingBox()
      expect(before && after && Math.abs(before.y - after.y)).toBeLessThan(2)

      const themeSwitch = page.getByRole('switch', { name: 'Dark theme' })
      if (await themeSwitch.getAttribute('aria-checked') === 'true') await themeSwitch.click()
      await page.screenshot({ path: testInfo.outputPath(`login-${viewport.width}-light.png`) })
      await themeSwitch.click()
      await expect(themeSwitch).toHaveAttribute('aria-checked', 'true')
      await page.screenshot({ path: testInfo.outputPath(`login-${viewport.width}-dark.png`) })

      const dimensions = await page.evaluate(() => ({
        scrollHeight: document.documentElement.scrollHeight,
        scrollWidth: document.documentElement.scrollWidth,
        innerHeight: window.innerHeight,
        innerWidth: window.innerWidth,
      }))
      expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.innerWidth + 2)
      expect(dimensions.scrollHeight).toBeLessThanOrEqual(dimensions.innerHeight + 2)

    }
  })

  test('keeps configured session bootstrap available in the organization view', async ({ page }) => {
    await page.route('**/runtime-config.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: 'window.__SKILLHUB_RUNTIME_CONFIG__ = { authSessionBootstrapEnabled: "true", authSessionBootstrapProvider: "proxy", authSessionBootstrapAuto: "false" }',
      })
    })

    await page.goto('/login')
    await expect(page.getByRole('button', { name: 'Sign in with group account' })).toBeVisible()
    await expect(page.getByLabel('Password', { exact: true })).toBeHidden()

    await page.getByRole('button', { name: 'Sign in with other methods' }).click()
    await expect(page.getByLabel('Password', { exact: true })).toBeVisible()
  })

  test('logs in to an explicit local destination on the isolated runtime', async ({ browser, page }) => {
    test.skip(process.env.SKILLHUB_RUNTIME_E2E !== '1', 'Requires an isolated runtime with fixture provisioning configured')
    test.setTimeout(90_000)
    const { username, password } = await createFreshSession(page)

    await page.goto('/login')
    await expect(page).toHaveURL('/')
    await page.goto('/login?returnTo=%2Fdashboard%2Ftokens%3Ftab%3Dactive%23latest')
    await expect(page).toHaveURL('/dashboard/tokens?tab=active#latest')
    await page.goto('/login?returnTo=%2Flogin')
    await expect(page).toHaveURL('/')

    const loginPage = await browser.newPage()
    await setEnglishLocale(loginPage)
    await loginPage.goto('/login?returnTo=%2Fdashboard%2Ftokens')
    await expect.poll(() => loginPage.evaluate(async () => (await fetch('/api/v1/auth/me')).status)).toBe(401)

    await loginPage.getByLabel('Username').fill(username)
    await loginPage.getByLabel('Password', { exact: true }).fill(password)
    await loginPage.getByRole('button', { name: 'Login' }).click()
    await expect(loginPage).toHaveURL('/dashboard/tokens')
    await expect.poll(() => loginPage.evaluate(async () => (await fetch('/api/v1/auth/me')).status)).toBe(200)
    await loginPage.close()

    const defaultLoginPage = await browser.newPage()
    await setEnglishLocale(defaultLoginPage)
    await defaultLoginPage.goto('/login')
    await defaultLoginPage.getByLabel('Username').fill(username)
    await defaultLoginPage.getByLabel('Password', { exact: true }).fill(password)
    await defaultLoginPage.getByRole('button', { name: 'Login' }).click()
    await expect(defaultLoginPage).toHaveURL('/')
    await defaultLoginPage.close()
  })
})
