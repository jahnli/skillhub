// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthAssetArtwork } from './auth-asset-artwork'

vi.mock('react-i18next', async () => {
  const { default: en } = await import('@/i18n/locales/en.json')
  const assets: Record<string, string> = en.authShell.assets
  return { useTranslation: () => ({ t: (key: string) => assets[key.replace('authShell.assets.', '')] }) }
})

let motionDisabled: boolean
let media: EventTarget

beforeEach(() => {
  vi.useFakeTimers()
  motionDisabled = false
  media = new EventTarget()
  Object.defineProperty(media, 'matches', { get: () => motionDisabled })
  vi.stubGlobal('matchMedia', () => media)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('AuthAssetArtwork', () => {
  it('animates decorative glass without a focusable control and clears the drift timer on unmount', () => {
    const view = render(<AuthAssetArtwork />)
    const artwork = view.container.firstElementChild as HTMLElement
    act(() => vi.advanceTimersByTime(100))
    expect(artwork.style.getPropertyValue('--orb-x')).toMatch(/px$/)
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Skills', 'Rules', 'MCP', 'Workflow', 'Agent'])
    expect(screen.queryByRole('button', { hidden: true })).toBeNull()
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('changes asset every two seconds and picks a fresh direction for each transition', () => {
    const random = vi.spyOn(Math, 'random').mockReturnValue(0.2)
    try {
      render(<AuthAssetArtwork />)
      const list = screen.getByRole('list')
      const active = () => list.querySelector('[data-active="true"]')?.textContent
      act(() => vi.advanceTimersByTime(1999))
      expect(active()).toBe('Skills')
      act(() => vi.advanceTimersByTime(1))
      expect(active()).toBe('Rules')
      expect(list.dataset.direction).toBe('-1')
      random.mockReturnValue(0.8)
      act(() => vi.advanceTimersByTime(2000))
      expect(active()).toBe('MCP')
      expect(list.dataset.direction).toBe('1')
      act(() => vi.advanceTimersByTime(6000))
      expect(active()).toBe('Skills')
    } finally {
      random.mockRestore()
    }
  })

  it('stops drift on mobile or reduced motion and restarts when motion is enabled', () => {
    motionDisabled = true
    const view = render(<AuthAssetArtwork />)
    const artwork = view.container.firstElementChild as HTMLElement
    act(() => vi.advanceTimersByTime(100))
    expect(artwork.style.getPropertyValue('--orb-x')).toBe('')
    motionDisabled = false
    act(() => { media.dispatchEvent(new Event('change')) })
    expect(artwork.style.getPropertyValue('--orb-x')).toMatch(/px$/)
    motionDisabled = true
    act(() => { media.dispatchEvent(new Event('change')) })
    expect(vi.getTimerCount()).toBe(0)
  })
})
