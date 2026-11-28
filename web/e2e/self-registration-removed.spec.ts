import { expect, test } from '@playwright/test'
import { csrfHeaders } from './helpers/csrf'
import { setEnglishLocale } from './helpers/auth-fixtures'

test('self-registration has no page or public account-creation endpoint', async ({ page }) => {
  await setEnglishLocale(page)
  await page.goto('/login')
  await expect(page.getByRole('link', { name: /sign up|register/i })).toHaveCount(0)
  await page.goto('/register')
  await expect(page.getByText('Not Found', { exact: true })).toBeVisible()
  await expect(page.locator('form')).toHaveCount(0)
  const response = await page.request.post('/api/v1/auth/local/register', {
    headers: await csrfHeaders(page),
    data: { username: 'retired_registration', password: 'Passw0rd!123', email: 'retired@example.test' },
  })
  expect(response.status()).toBe(404)
  expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)
})
