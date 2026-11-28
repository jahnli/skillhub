import { readFileSync } from 'node:fs'
import { defineConfig, loadEnv, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { validateBasePath } from './base-path-config'

const JS_BUILD_TARGET = 'es2020'
const LEGACY_BROWSER_TARGETS = ['chrome83', 'edge83', 'firefox78', 'safari14']
const basePath = validateBasePath(process.env.VITE_BASE_PATH ?? '/')
const guideTemplate = readFileSync(path.resolve(__dirname, 'src/docs/skill.md.template'), 'utf8')
const safeHostPattern = /^(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?$/

function registryGuidePlugin(): Plugin {
  const basePrefix = basePath === '/' ? '' : basePath.slice(0, -1)
  const guidePath = `${basePrefix}/registry/skill.md`
  const guideTemplatePath = `${basePrefix}/registry/skill.md.template`

  return {
    name: 'skillhub-cli-guide',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'registry/skill.md',
        source: guideTemplate,
      })
    },
    configureServer(server) {
      // Install after Vite's built-in Host check and reject malformed Host
      // values consistently with the production route. originalUrl survives
      // SPA/base rewrites.
      return () => {
        server.middlewares.use((request, response, next) => {
          const requestPath = new URL(request.originalUrl ?? request.url ?? '/', 'http://localhost').pathname
          if (requestPath === guideTemplatePath) {
            response.statusCode = 404
            response.end('Not Found')
            return
          }
          if (requestPath !== guidePath) {
            next()
            return
          }

          const host = request.headers.host
          if (!host || !safeHostPattern.test(host)) {
            response.statusCode = 400
            response.end('Invalid Host')
            return
          }

          response.statusCode = 200
          response.setHeader('Content-Type', 'text/markdown; charset=utf-8')
          response.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate')
          response.end(guideTemplate)
        })
      }
    },
  }
}

export default defineConfig(({ mode }) => {
  const devEnv = loadEnv(mode, __dirname, 'SKILLHUB_DEV_')
  const backendTarget = devEnv.SKILLHUB_DEV_API_TARGET ?? 'http://localhost:8080'
  const webPort = Number(devEnv.SKILLHUB_DEV_WEB_PORT || '3000')
  if (!Number.isInteger(webPort) || webPort < 1 || webPort > 65535) {
    throw new Error('SKILLHUB_DEV_WEB_PORT must be an integer between 1 and 65535')
  }

  return {
    base: basePath,
    plugins: [registryGuidePlugin(), react()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    build: {
      target: JS_BUILD_TARGET,
      cssTarget: LEGACY_BROWSER_TARGETS,
    },
    optimizeDeps: {
      esbuildOptions: {
        target: JS_BUILD_TARGET,
      },
    },
    test: {
      exclude: ['**/node_modules/**', '**/e2e/**'],
      testTimeout: 30000,
      hookTimeout: 30000,
    },
    server: {
      port: webPort,
      strictPort: true,
      watch: {
        usePolling: true,
        interval: 150,
      },
      proxy: {
        // Spring builds OAuth redirects from the browser-facing host and port.
        '/api/v1/auth/': {
          target: backendTarget,
          changeOrigin: false,
        },
        '/api': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/oauth2': {
          target: backendTarget,
          changeOrigin: false,
        },
        '/login/oauth2': {
          target: backendTarget,
          // Preserve the browser-facing host and port so Spring's
          // post-login redirect does not send the SPA to the backend port.
          changeOrigin: false,
        },
      },
    },
  }
})
