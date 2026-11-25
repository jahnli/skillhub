import { describe, expect, it } from 'vitest'
import { casdoorLoopbackCallback } from './casdoor-loopback-callback'

const issuer = 'https://sso.example.com'
const host = 'localhost:3000'
const callback = `http://${host}/api/v1/auth/login/callback`
const adapter = casdoorLoopbackCallback('oidc', issuer)

function authorize(entry: string, provider: string, redirectUri: string, silent: boolean) {
  const target = new URL('/login/oauth/authorize', provider)
  target.search = new URLSearchParams({ response_type: 'code', redirect_uri: redirectUri,
    state: 'a+b=', nonce: 'nonce', code_challenge: 'challenge', code_challenge_method: 'S256' }).toString()
  if (silent) target.searchParams.set('prompt', 'none')
  const response = { statusCode: 302, headers: { location: target.href } }
  adapter.adjustRedirect(response, { url: entry, headers: { host } })
  return new URL(response.headers.location)
}

describe('Casdoor HTTP development callback', () => {
  it.each([true, false])('requests a form response and preserves the authorization parameters (silent=%s)', silent => {
    const url = authorize('/oauth2/authorization/oidc', issuer, callback, silent)
    expect(Object.fromEntries(url.searchParams)).toEqual({ response_type: 'code', redirect_uri: callback,
      response_mode: 'form_post', state: 'a+b=', nonce: 'nonce', code_challenge: 'challenge',
      code_challenge_method: 'S256', ...(silent ? { prompt: 'none' } : {}) })
  })

  it.each([
    ['/oauth2/authorization/github', issuer, callback],
    ['/oauth2/authorization/oidc', 'https://other.example.com', callback],
    ['/oauth2/authorization/oidc', issuer, callback.replace('http:', 'https:')],
  ])('keeps the original response mode for %s, %s, %s', (entry, provider, redirectUri) => {
    expect(authorize(entry, provider, redirectUri, false).searchParams.has('response_mode')).toBe(false)
  })
})
