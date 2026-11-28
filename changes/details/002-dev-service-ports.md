# 配置本地开发服务端口并复用已运行后端

日期：2026-11-28

## 前端与代理

Vite 从 `web/.env.local` 等开发环境配置读取 `SKILLHUB_DEV_WEB_PORT`，未设置或为空时默认使用 `3000`；数值必须为 `1` 至 `65535` 的整数，否则加载配置失败。前端统一启用严格端口检查，端口被占用时启动失败，不自动递增到其他端口。Windows 启动器执行 Vite 时沿用该配置；命令行显式传入 `--port` 时仍以命令行参数为准。

同机运行时，优先保留 AI Gateway 后端/前端 `3000`/`5173`、Casdoor 后端/前端 `8000`/`7001`，SkillHub 可使用后端 `8001`、前端 `3002`；可选 Grafana 配置使用 `3001`。在 `web/.env.local` 设置 `SKILLHUB_DEV_WEB_PORT=3002` 即可配置前端；这是本机环境配置，不将 `3002` 改成所有检出的默认端口。

本地后端默认端口仍为 `8080`。在根目录 `.env.local.properties` 中设置 `server.port=8001`，并在 `web/.env.local` 中设置 `SKILLHUB_DEV_API_TARGET=http://localhost:8001`，可让 Vite 的 API 与 OAuth 代理使用自定义后端地址；未设置代理目标时回退到 `http://localhost:8080`。认证代理继续保留浏览器请求的主机和端口。

通过 Make 启动前端时，还需传入 `DEV_WEB_URL=http://localhost:3002`，使就绪检查与实际端口一致。Playwright 的托管服务命令显式使用 `3000`；检查已经运行的 `3002` 前端时，传入 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:3002`。OIDC 回调需按实际前端主机和端口在身份提供方登记，见[认证代理说明](001-casdoor-dev-callback.md)。

## 后端实例复用

Windows 启动器在构建前读取 `server.port` 并检查监听进程。仅当监听者是运行当前工作目录对应绝对路径 jar 的 `java.exe` 时复用；其他进程占用端口时直接报错，不终止已有进程。复用的后端独立运行，关闭启动器标签页不会停止它，需要在原终端停止后再重新构建。

## 扫描服务

扫描服务的 Compose 宿主机端口可通过根目录 `.env` 中的 `SKILLHUB_SCANNER_PORT` 配置，默认 `8000`，容器内端口仍为 `8000`。例如改为 `8002` 后，需要用原 Compose 文件组合重建 `skill-scanner`，并将宿主机运行的后端配置 `skillhub.security.scanner.base-url` 指向 `http://127.0.0.1:8002`；通过 Make 开发时还需传入 `DEV_SCANNER_URL=http://localhost:8002`。修改后端或 Vite 配置后应重启相应服务。

## 相关文件

- `start-dev.ps1`：读取后端端口、核对监听进程并提示复用实例的停止方式。
- `web/vite.config.ts`：读取和校验前端端口、启用严格端口检查，并配置 API 与 OAuth 代理目标。
- `docker-compose.yml`：配置扫描服务宿主机端口映射。
- `Makefile`：前端启动与就绪检查地址。
- `web/playwright.config.ts`：托管测试服务端口与外部测试地址入口。
- `docs/dev-workflow.md`：说明端口配置、服务重建和实例复用要求。
