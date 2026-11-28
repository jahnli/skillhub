# Development Workflow

This document describes the recommended workflow for developing SkillHub locally.

## Prerequisites

- Docker Desktop (for dependency services and staging)
- Java 21 (for running the backend locally)
- Node.js 22.13+ (22.x) or 24.x, and the pnpm version declared in `web/package.json` (frontend)
- Bun for CLI development; see [`cli/README.md`](../cli/README.md)
- `gh` CLI (for creating pull requests): https://cli.github.com/

## Stage 1: Local Development (fast iteration)

Use this stage for active development — writing code, fixing bugs, iterating quickly.

### Start the full local stack

```bash
make dev-all
```

This starts:
- Dependency services (Postgres, Redis, MinIO) via Docker
- Backend (Spring Boot) directly on your machine at http://localhost:8080
- Frontend (Vite) directly on your machine at http://localhost:3000

SkillHub now pins a shared Docker Compose project name for local development, so multiple git worktrees can reuse the same dependency containers instead of fighting over `5432`, `6379`, and `9000`.

On Windows, run the PowerShell launcher from the repository root:

```powershell
.\start-dev.ps1
```

The launcher requires Windows Terminal, Windows PowerShell, Java and Node.js/pnpm as listed above. It opens `SkillHub Backend` and `SkillHub Frontend` tabs in the shared `local-dev` window used by the AI Gateway/Casdoor launchers. Repeated launches reuse registered running tabs. The backend packages the Maven modules before running the jar with `--spring.profiles.active=local --spring.config.additional-location=file:../.env.local.properties` from `server/`. Prepare `.env.local.properties` in the repository root with your dependency connections and local settings; the launcher does not start Docker or set scanner/business-log environment overrides. The frontend installs dependencies when missing or when the lockfile is newer, then runs Vite with a strict port on `127.0.0.1` (override with `DEV_WEB_HOST`).

To use a different local backend port, set `server.port=8001` in the root `.env.local.properties` and `SKILLHUB_DEV_API_TARGET=http://localhost:8001` in `web/.env.local`. The Windows launcher's port check reads `server.port`; Vite uses `SKILLHUB_DEV_API_TARGET` for API and OAuth proxies, falling back to `http://localhost:8080` when unset. Restart the backend and frontend after changing these settings.

To avoid a frontend port conflict, set `SKILLHUB_DEV_WEB_PORT=3002` in `web/.env.local`. Vite defaults to `3000` when unset and fails if the selected port is occupied. For a shared machine, reserve AI Gateway's backend/frontend ports `3000`/`5173`, Casdoor's `8000`/`7001`, and use `8001`/`3002` for SkillHub; `3001` is already used by the optional Grafana configuration. With Make-based development, also pass `DEV_WEB_URL=http://localhost:3002` for readiness checks. To run Playwright against that running frontend, pass `PLAYWRIGHT_BASE_URL=http://127.0.0.1:3002`; its managed test server explicitly uses `3000`. Register the selected frontend origin and `/api/v1/auth/login/callback` path with your local identity provider when using OIDC login.

The scanner's Compose host port defaults to `8000`. To reserve that port for Casdoor, set `SKILLHUB_SCANNER_PORT=8002` in the root `.env`, recreate only `skill-scanner` with the same Compose files, and set `skillhub.security.scanner.base-url=http://127.0.0.1:8002` in `.env.local.properties`. The scanner still listens on `8000` inside its container. For Make-based development, also pass `DEV_SCANNER_URL=http://localhost:8002`.

SkillHub tabs are placed after all other registered projects, including AI Gateway and Casdoor, with `SkillHub Backend` followed by `SkillHub Frontend`. Other projects keep their relative order. Repeated launches also apply this ordering to existing registered tabs.

Manage dependency services separately. Application build/runtime errors remain visible in their tabs; the launcher does not wait for application health. Before building the backend, it checks the configured port and reuses a Java process running this checkout's packaged jar; a different process on that port causes an error. A reused backend keeps running independently when the launcher's tab closes. Stop it in its original terminal before rebuilding. The launcher tracks terminal sessions separately from `make dev-all`, so stop any existing frontend before switching launchers. For applications started in the launcher's tabs, press Ctrl+C in the application's tab, close that tab, then rerun `.\start-dev.ps1` to stop or rebuild. Close a failed application's tab before retrying as well; open idle tabs remain registered so tab ordering stays consistent.

### Backend restarts

**Frontend:** Vite HMR is enabled by default. Save a file and the browser updates instantly.

**Backend:** the local server now runs from a packaged Spring Boot jar instead of `spring-boot:run`. This avoids mixed classpaths across `skillhub-app`, `skillhub-auth`, `skillhub-domain`, and other sibling modules.

After editing backend code, restart the backend explicitly:

```bash
make dev-server-restart
```

If you are running the server in a foreground terminal instead of `make dev-all`, stop it and run `make dev-server` again. This rebuilds the backend modules before restart; duration depends on the machine and dependency cache.

### Mock authentication

Two mock users are available in local mode (no password needed):

| User ID       | Role        | Header                           |
|---------------|-------------|----------------------------------|
| `local-user`  | Regular user | `X-Mock-User-Id: local-user`   |
| `local-admin` | Super admin  | `X-Mock-User-Id: local-admin`  |

Local development also creates a password-based bootstrap admin by default.
Use `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` to log in through
the normal local account form. The default local fallback credentials are
`admin` / `ChangeMe!2026`.
To disable it for local source startup, set the environment variable
`BOOTSTRAP_ADMIN_ENABLED=false` before starting the backend.
For container or release environments, set the same value in `.env.release`
or the Compose environment.

### Useful commands

| Command                          | Description                      |
|----------------------------------|----------------------------------|
| `make dev-all`                   | Start full local stack           |
| `make dev-all-down`              | Stop all local services          |
| `make dev-status`                | Check status of all services     |
| `make dev-logs`                  | Tail backend logs                |
| `SERVICE=frontend make dev-logs` | Tail frontend logs               |
| `make dev-all-reset`             | Full reset (clears data volumes) |
| `make dev-server-restart`        | Restart backend after Java changes |
| `make namespace-smoke`           | Run namespace workflow smoke test |
| `make db-reset`                  | Reset database only              |

### Claude + Codex parallel workflow

When two agents need to work in parallel, do not point both of them at the same checkout. Create isolated task worktrees instead:

```bash
make parallel-init TASK=legal-pages
```

That creates dedicated Claude, Codex, and integration worktrees as sibling directories. Keep `localhost:3000` reserved for the integration worktree only.

After the one-time setup, switch to the integration worktree for the daily merge + verification loop:

```bash
cd ../skillhub-integration-legal-pages
make parallel-up
```

Then verify the merged result at http://localhost:3000.

Because all worktrees share the same local dependency project, you only need one set of Postgres, Redis, and MinIO containers for all of them.

If you need to inspect or resolve merge conflicts before starting the app, you can still split the flow manually:

```bash
cd ../skillhub-integration-legal-pages
make parallel-sync
make dev-all
```

See [13-parallel-workflow.md](./13-parallel-workflow.md) for the full workflow, responsibilities, merge rules, and recovery guidance.

## Stage 2: Staging Regression (pre-PR validation)

Use this stage when a feature or bugfix is complete and you want to verify it works correctly in a Docker environment before pushing.

### What staging does

`make staging` runs a **hybrid** Docker environment:
- **Backend**: built as a Docker image from your local source
- **Frontend**: built as static files (`pnpm build`) and served by Nginx
- **Dependencies**: same Postgres/Redis/MinIO as local dev

This is faster than building both images but still validates the containerized backend and the production Nginx serving path.

### Run staging

```bash
make staging
```

This will:
1. Build the backend Docker image
2. Build the frontend static files
3. Start all services
4. Run smoke tests against the API
5. Print pass/fail summary

`scripts/smoke-test.sh` verifies that the retired self-registration endpoint returns 404.
For authenticated user checks, supply an already provisioned test account through
`SMOKE_USERNAME` and `SMOKE_PASSWORD`; without both, that group is reported as skipped.
Password mutation runs only when `SMOKE_NEW_PASSWORD` is explicitly set; use a disposable
account because the script leaves that new password in effect. Suite Bundle smoke
requires these non-admin account credentials as well as administrator credentials.
Bootstrap creation remains independent of smoke credentials.
Admin smoke checks run only when `SMOKE_ADMIN_USERNAME` and
`SMOKE_ADMIN_PASSWORD` are provided. Use `SMOKE_ADMIN_CHECKS=false` when testing
a persistent environment where the current admin password is not part of the
smoke run.

If all tests pass, the environment stays running at:
- Web UI: http://localhost
- Backend API: http://localhost:8080

### Stop staging

```bash
make staging-down
```

### View staging logs

```bash
make staging-logs            # backend logs
SERVICE=web make staging-logs  # nginx logs
```

## Stage 3: Create Pull Request

After staging passes:

```bash
make pr
```

This will:
1. Check for uncommitted changes (prompts to commit if any)
2. Push your branch to origin
3. Create a pull request using `gh pr create --fill`

The PR title and body are auto-populated from your commit messages.

> **Note:** `make pr` requires an interactive terminal. Do not use it in CI.

## Full workflow summary

```
make dev-all          # start local dev
# ... write code, test in browser ...
make staging          # regression test in Docker
make staging-down     # stop staging
make pr               # push + create PR
```

## Documentation changes

The bilingual user-facing site lives in `docs/skillhub`; architecture and operator references live directly under `docs`. Keep Chinese and English workflows aligned and update the VitePress navigation when adding pages. Verify commands against the current source, not only older guides.

```bash
# Install site dependencies from the repository lockfile
npm --prefix docs/skillhub ci
make docs-build
make docs-preview
```

The site build checks internal links. Preview changed pages to check navigation, tables, and code blocks. Documentation-only changes do not require starting the application stack.
