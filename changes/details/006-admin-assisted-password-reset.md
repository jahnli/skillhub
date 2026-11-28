# 本地账号密码重置由管理员发起

日期：2026-11-29

## 找回密码

登录页已移除“忘记密码？”入口，原公开发码接口 `POST /api/v1/auth/local/password-reset/request` 返回 404。启用本地密码登录时，用户管理员（`USER_ADMIN`）或超级管理员（`SUPER_ADMIN`）可在用户管理页为已有本地凭证和邮箱的活跃非系统账号发起重置。

用户收到邮件后，在本实例的 `/reset-password` 填写账号邮箱、验证码和新密码。确认接口只接受管理员发起且尚在有效期内的未使用验证码。升级前自助申请的验证码即使尚未过期也无法使用，用户须联系管理员重新发码。已登录且知道原密码的用户仍可直接在安全设置中修改密码。

## 主要相关文件

- `web/src/pages/login.tsx` 和 `web/src/pages/reset-password.tsx` 对应登录入口和验证码确认表单。
- `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/LocalAuthController.java` 和 `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/local/PasswordResetService.java` 定义重置接口和验证码校验规则。
- 管理员发码依赖实例的邮件配置，配置及验证步骤见 [SMTP 配置指南](../../docs/19-smtp-password-reset-email-setup.md)。
