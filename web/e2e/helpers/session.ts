import { expect, type Page, type TestInfo } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { csrfHeaders } from './csrf'
import { fixtureClientIp } from './auth-fixtures'

const password = 'Passw0rd!123'
const cachedUserByWorker = new Map<number, string>()
const cachedSessionByAccount = new Map<string, SessionSnapshot>()
const requestTimeoutMs = process.env.CI ? 12_000 : 8_000

export interface TestCredentials {
  password: string
  username: string
}

interface RegisterSessionOptions {
  allowMockSession?: boolean
}

interface SessionSnapshot {
  username: string
  cookies: Array<{
    name: string
    value: string
    domain: string
    path: string
    expires: number
    httpOnly: boolean
    secure: boolean
    sameSite: 'Strict' | 'Lax' | 'None'
  }>
}

const cachedSessionByWorker = new Map<number, SessionSnapshot>()

function usernameForWorker(testInfo?: TestInfo): string {
  const worker = testInfo?.parallelIndex ?? 0
  return `e2e_worker_${worker}`
}

function uniqueUsernameForWorker(testInfo?: TestInfo): string {
  const worker = testInfo?.parallelIndex ?? 0
  const suffix = `${Date.now().toString(36)}${randomUUID().slice(0, 8)}`
  return `e2e_w${worker}_${suffix}`
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms))
}

function isRetryableStatus(status: number): boolean {
  return status === 429 || status >= 500
}

async function loginWithRetry(
  page: Page,
  username: string,
  currentPassword = password,
  retries = process.env.CI ? 10 : 6,
): Promise<boolean> {
  const request = page.context().request
  for (let i = 0; i < retries; i += 1) {
    try {
      const login = await request.post('/api/v1/auth/local/login', {
        data: { username, password: currentPassword },
        headers: await csrfHeaders(page, { 'X-Forwarded-For': fixtureClientIp(page) }),
        timeout: requestTimeoutMs,
      })

      if (login.ok()) {
        return true
      }

      const status = login.status()
      if (!isRetryableStatus(status)) {
        return false
      }
    } catch {
      // Request context can be transiently unstable in CI startup windows.
    }

    await sleep(250 * (i + 1))
  }

  return false
}

async function hasActiveSession(page: Page): Promise<boolean> {
  try {
    const response = await page.context().request.get('/api/v1/auth/me', {
      timeout: requestTimeoutMs,
    })
    return response.ok()
  } catch {
    return false
  }
}

async function cacheSession(page: Page, worker: number, username: string) {
  const snapshot = {
    username,
    cookies: await page.context().cookies(),
  }
  cachedSessionByWorker.set(worker, snapshot)
  cachedSessionByAccount.set(username, snapshot)
}

async function cacheAccountSession(page: Page, username: string) {
  cachedSessionByAccount.set(username, {
    username,
    cookies: await page.context().cookies(),
  })
}

async function restoreCachedSession(
  page: Page,
  worker: number,
  allowMockSession = true,
): Promise<SessionSnapshot | null> {
  const snapshot = cachedSessionByWorker.get(worker)
  if (!snapshot) {
    return null
  }

  if (!allowMockSession && snapshot.username === 'local-user') {
    return null
  }

  await page.context().addCookies(snapshot.cookies)
  if (await hasActiveSession(page)) {
    return snapshot
  }

  cachedSessionByWorker.delete(worker)
  return null
}

async function restoreCachedSessionForAccount(page: Page, username: string): Promise<SessionSnapshot | null> {
  const snapshot = cachedSessionByAccount.get(username)
  if (!snapshot) {
    return null
  }

  await page.context().addCookies(snapshot.cookies)
  if (await hasActiveSession(page)) {
    return snapshot
  }

  cachedSessionByAccount.delete(username)
  return null
}

async function primeAuthProviders(page: Page) {
  try {
    await page.context().request.get('/api/v1/auth/providers', { timeout: requestTimeoutMs })
  } catch {
    // Best effort warm-up.
  }
}

async function tryBootstrapMockSession(page: Page, worker: number): Promise<{ username: string, password: string } | null> {
  try {
    await page.context().request.get('/api/v1/auth/providers', {
      headers: { 'X-Mock-User-Id': 'local-user' },
      timeout: requestTimeoutMs,
    })
  } catch {
    return null
  }

  if (!(await hasActiveSession(page))) {
    return null
  }

  await cacheSession(page, worker, 'local-user')
  cachedUserByWorker.set(worker, 'local-user')
  return { username: 'local-user', password }
}

async function registerSessionOnce(page: Page, testInfo?: TestInfo, options?: RegisterSessionOptions) {
  const worker = testInfo?.parallelIndex ?? 0
  const allowMockSession = options?.allowMockSession !== false
  const cachedUsername = cachedUserByWorker.get(worker)
  const cached = !allowMockSession && cachedUsername === 'local-user' ? undefined : cachedUsername
  const username = usernameForWorker(testInfo)

  await primeAuthProviders(page)

  // Avoid hammering auth endpoints on every test run for the same worker.
  const restored = await restoreCachedSession(page, worker, allowMockSession)
  if (restored) {
    cachedUserByWorker.set(worker, restored.username)
    return { username: restored.username, password }
  }

  if (allowMockSession) {
    const mockSession = await tryBootstrapMockSession(page, worker)
    if (mockSession) {
      return mockSession
    }
  }

  // Prefer the known-good cached account to avoid repeated failed-logins on a fixed username.
  if (cached && await loginWithRetry(page, cached)) {
    await cacheSession(page, worker, cached)
    return { username: cached, password }
  }

  // Support environments where a deterministic worker account already exists.
  if (!cached && await loginWithRetry(page, username, password, process.env.CI ? 4 : 3)) {
    cachedUserByWorker.set(worker, username)
    await cacheSession(page, worker, username)
    return { username, password }
  }

  return createFreshSessionOnce(page, testInfo)
}

// Only the explicitly configured isolated E2E database receives fixture writes.
// The command is an argv array for psql, receives SQL on stdin, and never uses a shell.
function provisionFixtureAccount(username: string) {
  const configured = process.env.SKILLHUB_E2E_PSQL_COMMAND
  if (!configured) {
    throw new Error('Fresh accounts require SKILLHUB_E2E_PSQL_COMMAND for an isolated test database; self-registration is unavailable')
  }
  const command: unknown = JSON.parse(configured)
  if (!Array.isArray(command) || command.length === 0 || !command.every(value => typeof value === 'string')) {
    throw new Error('SKILLHUB_E2E_PSQL_COMMAND must be a non-empty JSON argv array')
  }
  if (!/^e2e_[a-zA-Z0-9_]+$/.test(username)) throw new Error('Invalid fixture username')
  const userId = `usr_${randomUUID()}`
  // Public test-only fixture password: Passw0rd!123. Never provision outside a disposable E2E database.
  const hash = '$2b$12$AasMZOX5df10NFdC6gbDVugYbWGB/cRHIMGmrRGegwt2rusNXmUQ.'
  const sql = `BEGIN;
INSERT INTO user_account(id, display_name, email, status) VALUES ('${userId}', '${username}', '${username}@example.test', 'ACTIVE');
INSERT INTO local_credential(user_id, username, password_hash) VALUES ('${userId}', '${username}', '${hash}');
INSERT INTO namespace_member(namespace_id, user_id, role) VALUES ((SELECT id FROM namespace WHERE slug = 'global'), '${userId}', 'MEMBER');
COMMIT;`
  execFileSync(command[0], command.slice(1), {
    input: sql, cwd: fileURLToPath(new URL('../../../', import.meta.url)),
    stdio: ['pipe', 'pipe', 'pipe'], timeout: 15_000,
  })
}

async function createFreshSessionOnce(page: Page, testInfo?: TestInfo) {
  const worker = testInfo?.parallelIndex ?? 0
  const username = uniqueUsernameForWorker(testInfo)
  provisionFixtureAccount(username)
  await primeAuthProviders(page)
  expect(await loginWithRetry(page, username)).toBeTruthy()
  cachedUserByWorker.set(worker, username)
  await cacheSession(page, worker, username)
  return { username, password }
}

export async function registerSession(page: Page, testInfo?: TestInfo, options?: RegisterSessionOptions) {
  let lastError: unknown

  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await registerSessionOnce(page, testInfo, options)
    } catch (error) {
      lastError = error
      if (attempt < 2) {
        await sleep(500 * (attempt + 1))
      }
    }
  }

  throw lastError
}

export async function createFreshSession(page: Page, testInfo?: TestInfo) {
  let lastError: unknown

  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await createFreshSessionOnce(page, testInfo)
    } catch (error) {
      lastError = error
      if (attempt < 2) {
        await sleep(500 * (attempt + 1))
      }
    }
  }

  throw lastError
}

export async function loginWithCredentials(page: Page, credentials: TestCredentials, _testInfo?: TestInfo) {
  await primeAuthProviders(page)

  const restored = await restoreCachedSessionForAccount(page, credentials.username)
  if (restored) {
    return credentials
  }

  const loggedIn = await loginWithRetry(
    page,
    credentials.username,
    credentials.password,
    process.env.CI ? 12 : 8,
  )
  expect(loggedIn).toBeTruthy()

  await cacheAccountSession(page, credentials.username)
  return credentials
}
