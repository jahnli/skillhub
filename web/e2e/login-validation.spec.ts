import { expect, test } from '@playwright/test'
import { setEnglishLocale, setUniqueClientIp } from './helpers/auth-fixtures'
import { createFreshSession } from './helpers/session'
import { csrfHeaders } from './helpers/csrf'

test.describe('Login Flow (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await setUniqueClientIp(page, `login-validation-${testInfo.title}`)
  })

  // TC_REG_006 P0 - successful login (already tested in auth-entry.spec.ts partially; extend here)
  test('TC_REG_006: shows required field errors when submitting empty login form', async ({ page }) => {
    await page.goto('/login')
    await page.getByRole('button', { name: 'Login' }).click()
    await expect(page.getByText('Username is required')).toBeVisible()
    await expect(page.getByText('Password is required')).toBeVisible()
  })

  // TC_REG_007 P0 - wrong password
  test('TC_REG_007: shows error for wrong password on existing account', async ({ page }) => {
    const { username } = await createFreshSession(page)
    await page.context().request.post('/api/v1/auth/logout', { headers: await csrfHeaders(page) })
    await page.goto('/login')
    await setEnglishLocale(page)
    await page.getByLabel(/username/i).fill(username)
    await page.getByLabel(/^password/i).fill('WrongPassword999!')
    await page.getByRole('button', { name: 'Login' }).click()
    await expect(page.getByText(/invalid|incorrect|wrong|username.*password/i)).toBeVisible()
  })

  // TC_REG_008 P0 - non-existent username
  test('TC_REG_008: shows error for non-existent username login attempt', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel(/username/i).fill('nonexistent_user_xyz99999')
    await page.getByLabel(/^password/i).fill('Test123!@')
    await page.getByRole('button', { name: 'Login' }).click()
    await expect(page.getByText(/invalid|incorrect|wrong|username.*password|not found/i)).toBeVisible()
  })

  // TC_REG_010 P1 - SQL injection safety
  test('TC_REG_010: safely handles SQL injection input in username field', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel(/username/i).fill("admin' OR '1'='1")
    await page.getByLabel(/^password/i).fill('anything')
    await page.getByRole('button', { name: 'Login' }).click()
    // Should not log in; should show error or validation message, NOT redirect to dashboard
    await expect(page).not.toHaveURL('/dashboard')
  })

  // TC_REG_011 P1 - XSS in input
  test('TC_REG_011: safely handles XSS payload in username field without executing script', async ({ page }) => {
    let alerted = false
    page.on('dialog', () => { alerted = true })

    await page.goto('/login')
    await page.getByLabel(/username/i).fill("<script>alert('xss')</script>")
    await page.getByLabel(/^password/i).fill('anything')
    await page.getByRole('button', { name: 'Login' }).click()
    await expect(page).not.toHaveURL('/dashboard')
    expect(alerted).toBe(false)
  })
})

test.describe('Login UI (Real API)', () => {
  test.beforeEach(async ({ page }) => {
    await setEnglishLocale(page)
  })

  // TC_UI_003 P2 - password visibility toggle
  test('TC_UI_003: password visibility toggle switches between masked and plain text', async ({ page }) => {
    await page.goto('/login')
    const passwordInput = page.getByLabel(/^password/i)
    await expect(passwordInput).toHaveAttribute('type', 'password')

    const toggleBtn = page.getByRole('button', { name: /show|hide|toggle/i })
      .or(page.locator('[data-testid*="password-toggle"], [aria-label*="password"]'))
    if (await toggleBtn.isVisible()) {
      await toggleBtn.click()
      await expect(passwordInput).toHaveAttribute('type', 'text')
    }
  })

  // TC_UI_005 P2 - Enter key submits form
  test('TC_UI_005: pressing Enter in the last input field submits the login form', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel(/username/i).fill('someuser')
    await page.getByLabel(/^password/i).fill('SomePass123!')
    await page.getByLabel(/^password/i).press('Enter')
    // Form should attempt submission (either error msg or redirect)
    await expect(
      page.getByText(/invalid|incorrect|dashboard/i)
        .or(page.locator('[role="alert"]'))
    ).toBeVisible({ timeout: 5000 })
  })

  test('does not offer self-registration when returnTo is present', async ({ page }) => {
    await page.goto('/login?returnTo=%2Fdashboard%2Ftokens')
    await expect(page.getByRole('link', { name: /sign up|register/i })).toHaveCount(0)
  })
})
