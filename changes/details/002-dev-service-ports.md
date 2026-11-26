# 配置本地开发服务端口并复用已运行后端

日期：2026-11-26

本地后端默认端口仍为 `8080`。在根目录 `.env.local.properties` 中设置 `server.port=8001`，并在 `web/.env.local` 中设置 `SKILLHUB_DEV_API_TARGET=http://localhost:8001`，可让 Vite 的 API 与 OAuth 代理使用自定义后端地址；未设置代理目标时回退到 `http://localhost:8080`。认证代理继续保留浏览器请求的主机和端口。

Windows 启动器在构建前读取 `server.port` 并检查监听进程。仅当监听者是运行当前工作目录对应绝对路径 jar 的 `java.exe` 时复用；其他进程占用端口时直接报错，不终止已有进程。复用的后端独立运行，关闭启动器标签页不会停止它，需要在原终端停止后再重新构建。

扫描服务的 Compose 宿主机端口可通过根目录 `.env` 中的 `SKILLHUB_SCANNER_PORT` 配置，默认 `8000`，容器内端口仍为 `8000`。例如改为 `8002` 后，需要用原 Compose 文件组合重建 `skill-scanner`，并将宿主机运行的后端配置 `skillhub.security.scanner.base-url` 指向 `http://127.0.0.1:8002`；通过 Make 开发时还需传入 `DEV_SCANNER_URL=http://localhost:8002`。修改后端或 Vite 配置后应重启相应服务。

## 相关文件

- `start-dev.ps1`：读取后端端口、核对监听进程并提示复用实例的停止方式。
- `web/vite.config.ts`：从开发环境配置读取 API 与 OAuth 代理目标。
- `docker-compose.yml`：配置扫描服务宿主机端口映射。
- `docs/dev-workflow.md`：说明端口配置、服务重建和实例复用要求。
