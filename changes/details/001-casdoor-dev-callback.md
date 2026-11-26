# 调整本地 Casdoor 认证代理

日期：2026-11-26

本地通过 `http://localhost:3000` 访问 SkillHub 时，Vite 将 `/api/v1/auth/`、`/oauth2` 和 `/login/oauth2` 请求转发给 `http://localhost:8080`，并保留浏览器请求中的主机和端口，供 Spring 生成 OAuth 重定向地址。通用 `/api` 代理仍将 `Host` 改写为后端地址。上述代理均不再启用 `xfwd` 来追加转发头。

本地开发移除了将 Casdoor 授权响应改为 `form_post` 并把回调 POST 转为 GET 的适配器，也移除了 Vite 通过 PFX 启用 HTTPS 的配置。`SKILLHUB_DEV_CASDOOR_REGISTRATION_ID`、`SKILLHUB_DEV_CASDOOR_ISSUER`、`SKILLHUB_DEV_HTTPS_PFX` 和 `SKILLHUB_DEV_HTTPS_PASSPHRASE` 不再参与 Vite 配置。使用本地 HTTP 时，需在 Casdoor 登记 `http://localhost:3000/api/v1/auth/login/callback` 和 `http://localhost:3000/api/v1/auth/logout/callback`，并使 Spring 的登录回调配置与登记地址一致。

## 相关文件

- `web/vite.config.ts` 配置认证代理并移除本地 HTTPS 和回调插件入口。
- `web/casdoor-loopback-callback.ts` 已删除，原用于改写授权响应和转换本地回调请求。
- `web/casdoor-loopback-callback.test.ts` 已删除，原用于验证回调适配器。
- `docs/casdoor-sso-implementation.md` 移除旧适配器说明并保留现行回调登记要求。
- `docs/dev-workflow.md` 移除旧适配器的开发环境变量配置说明。
