# 移除本地账号自助注册

日期：2026-11-28

## 账号与接口

登录页移除了注册入口，访问 `/register` 显示页面未找到。服务端移除了 `POST /api/v1/auth/local/register`。本地认证能力接口、系统认证设置接口和管理页面均已移除 `selfRegistrationEnabled` 字段或开关。超级管理员仍可管理密码登录开关，既有本地账号登录、初始管理员建号、外部身份首次登录的准入和建号流程继续适用。

## 部署与升级

旧版数据库 `auth.local` 设置中的 `selfRegistrationEnabled` 可兼容读取，保存时只写入 `passwordLoginEnabled`。升级无需迁移历史记录。若已保存新格式，回退到旧应用前需先恢复旧版可读的设置，并记录操作。旧注册环境变量已失效，Helm 升级时应从自定义 values 中移除 `auth.initialSettings.selfRegistrationEnabled`。详见[认证设置说明](../../docs/923-auth-system-settings.md)。

冒烟检查使用预置测试账号。通用脚本在提供 `SMOKE_USERNAME` 和 `SMOKE_PASSWORD` 后检查账号会话，提供 `SMOKE_NEW_PASSWORD` 后才执行改密。未提供账号时仍检查旧注册接口返回 404。

## 主要相关文件

- `web/src/app/router.tsx`、`web/src/pages/admin/system-config.tsx` 和 `web/src/api/generated/schema.d.ts` 对应页面、管理设置和前端接口契约。
- `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/LocalAuthController.java` 和 `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/settings/LocalAuthSettingsService.java` 对应公开接口和旧配置兼容。
- `charts/skillhub/values.schema.json`、`scripts/smoke-test.sh`、`web/e2e/self-registration-removed.spec.ts` 对应升级配置和注册入口移除后的验收。
