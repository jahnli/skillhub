# 集团账号登录入口与资产展示

日期：2026-11-28

## 登录方式

打开 `/login` 时，已启用企业会话接入的部署优先使用该入口。其他部署从服务端登录方式中选取第一个提供方标识为 `oidc`、`casdoor` 或 `enterprise-sso` 的 OAuth 入口，作为“集团账号登录”主按钮。按钮使用服务端返回的授权地址，并保留原页面的安全站内返回路径。

同时启用密码登录或其他身份源时，用户可通过“使用其他方式登录”查看可用选项，并可切回集团账号登录。没有集团入口时直接列出可用方式。如果配置读取失败且无法确认任何可用方式，页面提供重试。

## 资产展示

桌面左侧展示站点标识、AI 资产标题和淡蓝水晶球。球内依次展示 Skills、Rules、MCP、Workflow、Agent，每类图标和名称共用一种颜色。每 2 秒切换一项，每次随机向左或向右旋转，五项轮换一圈共 10 秒。启用系统“减少动态效果”时，五项同时静态显示。视口宽度小于 1024px 时隐藏插画，保留品牌文字和登录操作。

## 主要相关文件

- `web/src/pages/login.tsx`、`web/src/features/auth/login-button.tsx`、`web/src/features/auth/session-bootstrap-entry.tsx` 实现方式选择、会话接入和跳转。
- `web/src/features/auth/auth-shell.tsx`、`web/src/features/auth/auth-shell.module.css`、`web/src/features/auth/login-actions.module.css` 定义布局和登录按钮。
- `web/src/features/auth/auth-asset-artwork.tsx`、`web/src/features/auth/auth-asset-artwork.module.css` 定义五类资产的配色、切换与静态布局。
