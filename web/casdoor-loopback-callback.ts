import type { IncomingMessage, ServerResponse } from 'node:http'
import { loadEnv, type Plugin } from 'vite'

// Only the Vite development server calls this adapter. Spring remains responsible for
// correlating state with its session and exchanging the code with the PKCE verifier.
export function casdoorLoopbackCallback(registration: string, issuer: string) {
  const entry = `/oauth2/authorization/${registration}`
  const callback = `/login/oauth2/code/${registration}`
  const allowed = new Set(['state', 'iss', 'code', 'error', 'error_description', 'error_uri'])
  const maxBytes = 4096
  const loopbackHost = /^localhost(?::\d{1,5})?$/i

  function adjustRedirect(proxyResponse: Pick<IncomingMessage, 'statusCode' | 'headers'>,
    request: Pick<IncomingMessage, 'url' | 'headers'>) {
    const location = proxyResponse.headers.location
    const host = request.headers.host ?? ''
    if (!location || proxyResponse.statusCode !== 302 || !loopbackHost.test(host)
      || request.url?.split('?')[0] !== entry) return

    if (!URL.canParse(location)) return
    const target = new URL(location)
    if (target.origin !== issuer || target.searchParams.get('response_type') !== 'code'
      || target.searchParams.get('redirect_uri') !== `http://${host}${callback}`) return

    target.searchParams.set('response_mode', 'form_post')
    proxyResponse.headers.location = target.href
  }

  async function handleForm(request: IncomingMessage, response: ServerResponse, next: () => void) {
    if (request.method !== 'POST' || request.url !== callback || !loopbackHost.test(request.headers.host ?? '')) {
      next()
      return
    }
    response.setHeader('Cache-Control', 'no-store')
    response.setHeader('Referrer-Policy', 'no-referrer')
    // Edge can serialize the origin as "null" when posting from HTTPS to HTTP.
    const origin = request.headers.origin
    if (origin !== issuer && origin !== 'null') {
      response.writeHead(403).end()
      return
    }
    if (request.headers['content-type']?.split(';')[0] !== 'application/x-www-form-urlencoded') {
      response.writeHead(415).end()
      return
    }
    const chunks: Buffer[] = []
    let bytes = 0
    for await (const chunk of request) {
      bytes += chunk.length
      if (bytes > maxBytes) {
        response.writeHead(413).end()
        return
      }
      chunks.push(chunk)
    }
    const params = new URLSearchParams(Buffer.concat(chunks).toString('utf8'))
    if (!params.get('state') || params.get('iss') !== issuer || params.has('code') === params.has('error')
      || !(params.get('code') || params.get('error'))
      || [...params.keys()].some(key => !allowed.has(key) || params.getAll(key).length !== 1)) {
      response.writeHead(400).end()
      return
    }
    // The top-level GET carries the original SameSite=Lax session cookie.
    response.writeHead(303, { Location: `${callback}?${params}` }).end()
  }
  return { adjustRedirect, handleForm }
}

export function casdoorLoopbackPlugin(): Plugin {
  return {
    name: 'casdoor-loopback-callback',
    configureServer(server) {
      const proxy = server.config.server.proxy?.['/oauth2']
      if (server.config.server.https || !proxy || typeof proxy === 'string') return
      const env = loadEnv(server.config.mode, server.config.envDir, 'SKILLHUB_DEV_CASDOOR_')
      const registration = env.SKILLHUB_DEV_CASDOOR_REGISTRATION_ID
      const issuer = env.SKILLHUB_DEV_CASDOOR_ISSUER
      if (!registration || !issuer) return

      const callback = casdoorLoopbackCallback(registration, issuer)
      proxy.configure = proxyServer => proxyServer.on('proxyRes', callback.adjustRedirect)
      server.middlewares.use((req, res, next) => callback.handleForm(req, res, next).catch(next))
    },
  }
}
