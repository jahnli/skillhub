import { describe, expect, it, vi } from 'vitest'
import React from 'react'

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
    }),
  }
})

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
}))

const { cachedProfile } = vi.hoisted(() => ({ cachedProfile: { data: null as Record<string, string> | null } }))

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => cachedProfile,
  useQueryClient: () => ({ invalidateQueries: vi.fn(), setQueryData: vi.fn() }),
}))

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    status?: number
  },
  profileApi: {
    getProfile: vi.fn(),
    updateProfile: vi.fn(),
  },
}))

vi.mock('@/features/auth/use-auth', () => ({
  useAuth: () => ({ user: { displayName: 'Test', avatarUrl: null, email: 'test@test.com', canChangePassword: true } }),
}))

vi.mock('@/shared/lib/error-display', () => ({
  truncateErrorMessage: (v: string) => v,
}))

vi.mock('@/shared/lib/toast', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
  CardContent: ({ children }: { children: unknown }) => children,
  CardDescription: ({ children }: { children: unknown }) => children,
  CardHeader: ({ children }: { children: unknown }) => children,
  CardTitle: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/input', () => ({
  Input: () => null,
}))

import { renderToStaticMarkup } from 'react-dom/server'
import { ProfileSettingsPage } from './profile'

describe('ProfileSettingsPage', () => {
  it('shows employee fields from the self-profile response', () => {
    cachedProfile.data = { company: 'Example', department: 'Operations', employeeNumber: '00123', jobTitle: 'Engineer', phone: '+86-123' }
    try {
      const html = renderToStaticMarkup(React.createElement(ProfileSettingsPage))
      for (const value of Object.values(cachedProfile.data)) expect(html).toContain(value)
    } finally {
      cachedProfile.data = null
    }
  })

  it('exports a named component function', () => {
    expect(typeof ProfileSettingsPage).toBe('function')
  })

  it('renders reset-password entry action', () => {
    const html = renderToStaticMarkup(React.createElement(ProfileSettingsPage))
    expect(html).toContain('profile.resetPassword')
  })
})
