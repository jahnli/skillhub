# 调整 Casdoor 认证代理

日期：2026-11-27

## 本地开发

本地通过 Vite 前端地址（默认 `http://localhost:3000`，端口配置见[本地开发服务端口](002-dev-service-ports.md)）访问 SkillHub 时，Vite 将 `/api/v1/auth/`、`/oauth2` 和 `/login/oauth2` 请求转发给配置的后端地址（默认 `http://localhost:8080`），并保留浏览器请求中的主机和端口，供 Spring 生成 OAuth 重定向地址。通用 `/api` 代理仍将 `Host` 改写为后端地址。上述代理均不再启用 `xfwd` 来追加转发头。

本地开发移除了将 Casdoor 授权响应改为 `form_post` 并把回调 POST 转为 GET 的适配器，也移除了 Vite 通过 PFX 启用 HTTPS 的配置。`SKILLHUB_DEV_CASDOOR_REGISTRATION_ID`、`SKILLHUB_DEV_CASDOOR_ISSUER`、`SKILLHUB_DEV_HTTPS_PFX` 和 `SKILLHUB_DEV_HTTPS_PASSPHRASE` 不再参与 Vite 配置。使用本地 HTTP 时，需按实际前端地址在 Casdoor 登记 `/api/v1/auth/login/callback` 和 `/api/v1/auth/logout/callback`；默认地址分别为 `http://localhost:3000/api/v1/auth/login/callback` 和 `http://localhost:3000/api/v1/auth/logout/callback`。Spring 的登录回调配置须与登记地址一致，自定义前端端口或改用 `127.0.0.1` 时同步更新登记地址。

## 容器部署

Nginx 将 `/api/` 的上游响应头缓冲区设为 `16k`，以容纳联合登出时 `X-SkillHub-Logout-Url` 响应头中携带 `id_token_hint` 的 Casdoor 跳转地址。该路径的代理响应缓冲区配置为 `8 16k`，向客户端发送响应时可占用的缓冲区上限为 `16k`。

## 相关文件

- `web/vite.config.ts` 配置认证代理并移除本地 HTTPS 和回调插件入口。
- `web/casdoor-loopback-callback.ts` 已删除，原用于改写授权响应和转换本地回调请求。
- `web/casdoor-loopback-callback.test.ts` 已删除，原用于验证回调适配器。
- `docs/casdoor-sso-implementation.md` 移除旧适配器说明并保留现行回调登记要求。
- `docs/dev-workflow.md` 移除旧适配器的开发环境变量配置说明。
- `web/nginx.conf.template` 配置 `/api/` 的上游响应缓冲区。
