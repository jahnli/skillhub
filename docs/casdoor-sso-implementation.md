# Casdoor 单点登录与员工资料同步实现说明

本文记录 `v1-linqiang` 分支中 Casdoor 与 SkillHub 的登录/退出联动和员工资料同步。内容按当前源码和提交整理；它描述的是 SkillHub 当前实现，不代表任意 Casdoor 部署已经完成相应配置或真实账号端到端验收。

## 1. 需求与范围

本次实现围绕三个行为：

1. 用户已在同一浏览器登录 Casdoor 时，打开 SkillHub 自动尝试登录，不要求先点击登录按钮。
2. Casdoor 注销后，SkillHub 对应会话失效；用户在 SkillHub 主动退出时，也发起 Casdoor 注销。
3. Casdoor 登录时导入姓名、邮箱、头像、公司、部门、工号、岗位、手机号；首次建号导入，后续登录更新。

实现仍使用项目已有 OAuth2/OIDC 登录、身份绑定、Spring Session 和用户资料 API。它不是浏览器之间共享 Casdoor Cookie，也不将 Casdoor 的用户 ID 作为 SkillHub 内部用户 ID。

## 2. 主要代码位置

| 范围 | 文件 | 职责 |
|---|---|---|
| OAuth 注册配置 | 仓库根目录的 `.env.local.properties`（本地启动时显式加载） | Casdoor client、issuer、回调地址和 OIDC scope |
| OIDC 登录与身份 | `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/oauth/CustomOidcUserService.java`、`OAuthLoginFlowService.java`、`OAuth2LoginSuccessHandler.java` | 读取 OIDC 声明、走现有身份绑定/开户流程、建立 SkillHub 会话并保留 ID Token |
| 自动登录前端 | `web/src/bootstrap.ts`、`web/src/features/auth/casdoor-session.ts` | 应用挂载前探测登录状态并决定是否静默跳转 Casdoor |
| Casdoor 注销联动 | `server/skillhub-auth/src/main/java/com/iflytek/skillhub/auth/oauth/CasdoorSessionLogout.java`、`SecurityConfig.java` | 浏览器注销跳转、Back-Channel Logout 验证与会话撤销 |
| 员工资料持久化 | `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/user/UserAccount.java`、`EmployeeProfile.java`、`server/skillhub-app/src/main/resources/db/migration/V127__employee_profile.sql` | 增加员工字段并保存至用户表 |
| 资料 API/UI | `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/UserProfileController.java`、`web/src/pages/settings/profile.tsx` | 向当前用户返回资料并在个人资料页展示 |

主要提交：`2d26e5fb`（会话联动及字段模型）、`39023a95`（员工资料同步）、`dd218def`（静默登录及注销联动调整）。

## 3. 登录流程

### 3.1 用户打开 SkillHub

1. `web/src/bootstrap.ts` 在加载主 React 应用前调用 `getCasdoorSignInUrl()`。
2. 前端先请求 `GET /api/v1/auth/me`。已有 SkillHub 会话的用户直接进入原页面。
3. 匿名访问时查询提供方列表，启用静默登录的 Casdoor 提供方才会触发授权。会话查询失败时继续加载页面。
4. 浏览器访问后端 `/oauth2/authorization/{registrationId}`，携带 `silent=true`。返回地址保留业务页路径、查询参数和锚点，并附上 `skillhubSsoChecked=1`。回到业务页后，前端消费标记并清理地址栏，下一次刷新时重新查询 SkillHub 会话。
5. 授权请求使用 OIDC `prompt=none` 和 PKCE。收到与本轮请求匹配的 `login_required` 时，后端核对会话 ID、用户 ID 和登录来源，再使原企业会话失效。要求同意授权或选择账号等错误不会触发这条注销逻辑。
6. Spring Security 校验 OAuth `state`、PKCE 与 OIDC 响应，`CustomOidcUserService` 将身份交给现有 `OAuthLoginFlowService`/`IdentityBindingService`。身份绑定仍以提供方与其 `sub` 为准，邮箱只有在 `email_verified=true` 时作为可信邮箱。
7. 登录成功处理器建立 SkillHub 会话，记录 OIDC ID Token 供后续注销联动使用，并按保存的 `returnTo` 返回原页面。

认证回调、登录、注册及 CLI/device 等入口跳过自动核验。显式注销返回时消费 `signedOut=1` 并清理地址栏。Casdoor 通过后通道通知撤销已登录用户的会话。通知生效后，前端在下次请求 `/me` 时感知退出。

## 4. 登录退出联动

### 4.1 SkillHub 注销 Casdoor

用户菜单调用 `POST /api/v1/auth/logout?federated=true`。后端先注销 SkillHub session 并删除会话 Cookie；仅当当前认证身份来自配置的 Casdoor registration 时，才根据 OIDC provider metadata 中的 `end_session_endpoint` 生成 Casdoor 注销 URL。响应通过 `X-SkillHub-Logout-Url` 返回地址，前端再让浏览器导航到该地址。

跳转会携带可用的 `id_token_hint`（旧会话没有保留 ID Token 时使用 `client_id`），以及独立的退出回调 `post_logout_redirect_uri`（`/api/v1/auth/logout/callback`）。因此 Casdoor 应用必须允许该确切的登出后跳转 URI；SkillHub 不接受 Casdoor 任意指定的回跳地址。

用户在非 Casdoor 登录方式下退出，只清除 SkillHub 会话，不尝试退出 Casdoor。手动访问通用登出端点与前端 federated 登出流程的响应也有所区别。

### 4.2 在 Casdoor 注销 SkillHub

Casdoor 应配置 OIDC Back-Channel Logout 指向：

```text
POST /api/v1/auth/back-channel-logout
```

后端使用 Casdoor issuer 的 JWK 验证签名，并校验 issuer、audience、Logout Token 时间、`jti`、无 `nonce` 以及 Back-Channel Logout event。合法通知在 Redis 写入以 `sid` 为优先、否则以 `sub` 为键的撤销时间标记；Lua 脚本只推进时间，不允许重放旧 token 撤销更新的登录会话。

此端点只接受 `application/x-www-form-urlencoded` 中唯一的 `logout_token` 字段，并单独从 CSRF 检查中豁免，因为它是服务端到服务端的协议回调。其他请求不因此放宽 CSRF。

SkillHub 在该用户后续请求时检查其保存的 ID Token 是否早于 Redis 撤销标记；匹配时失效本地 session 并清理 Security Context。前端当前用户查询也会定期刷新，因此已打开的页面通常在下一次请求/刷新时反映失效状态，而不是保证所有在线页面在收到通知的同一瞬间同步变化。

## 5. 员工资料同步

### 5.1 数据字段与来源

资料在 SkillHub `user_account` 中保存。迁移 `V127__employee_profile.sql` 增加以下可空列：

| SkillHub 字段 | 数据库列 | 当前 Casdoor 声明读取位置 |
|---|---|---|
| 公司 `company` | `employee_company` | `affiliation` |
| 部门 `department` | `employee_department` | `properties.department` |
| 工号 `employeeNumber` | `employee_number` | `properties.job_number` |
| 岗位 `jobTitle` | `employee_job_title` | `properties.job_type` |
| 手机号 `phone` | `employee_phone` | `phone` |
| 姓名 `displayName` | 既有显示名列 | `displayName`，为空时取 `name` |
| 邮箱 `email` | 既有邮箱列 | `email` 且 `email_verified=true` 才作为可信邮箱 |
| 头像 `avatarUrl` | 既有头像列 | `avatar`，为空时取 `avatar_url`；OIDC `picture` 会归一化为 `avatar_url` |

字段映射以 `IdentityBindingService.updateCasdoorProfile()` 实际使用的声明为准。若 Casdoor 应用释放的名称不同，需要调整 Casdoor claim/property 映射或代码映射；Scope 包含 `profile,email,phone` 并不自动保证自定义员工属性已在 UserInfo/ID Token 中释放。

### 5.2 首次登录与后续更新

首次通过 Casdoor 建立 SkillHub 用户和身份绑定时，初始化姓名、可信邮箱、头像及员工字段。已有身份再次登录时，`IdentityBindingService` 重新读取并保存 Casdoor 资料；并发登录发生唯一键冲突时，会重新读取获胜绑定并刷新资料。Casdoor 用户资料变更后，需要下一次成功登录才能同步到 SkillHub。

自定义员工字段采用“未提供或为空白时保留既有值、提供非空字符串时更新”的语义；当前不能通过空字符串清空已有员工字段。姓名和头像在未提供时不会被清空；邮箱未验证时不写入可信邮箱。

个人资料 API 将这五个员工属性附加在既有当前用户资料响应中，前端个人资料页读取并展示。员工字段由身份提供方维护；资料页的展示不等同于允许用户直接修改这些字段。

## 6. 配置与部署检查

本地以 `local` profile 运行时，通过 `--spring.config.additional-location=file:../.env.local.properties` 从外部文件加载注册信息。当前注册名为 `oidc`，以下为该文件的部分键：

```properties
spring.security.oauth2.client.registration.oidc.provider=oidc
spring.security.oauth2.client.provider.oidc.issuer-uri=https://<casdoor-host>
spring.security.oauth2.client.registration.oidc.redirect-uri=http://localhost:3000/api/v1/auth/login/callback
```

`oidc` 是 Spring 注册名，用于授权入口和已有身份绑定。本部署的提供方也命名为 `oidc`，其 `issuer-uri` 指向公司 Casdoor。静默登录、联动注销和员工资料同步共用这项提供方配置。实际 redirect URI 必须与 Casdoor 应用许可列表及 Spring 当前注册配置一致，协议、主机、端口和路径均须完全匹配。本地 Casdoor 重定向列表还需登记 `http://localhost:3000/api/v1/auth/logout/callback`。切换前先登记两个新地址。

不要把 client secret 写入前端变量、提交到 Git 或粘贴到日志。部署 Casdoor scope/claim 时，需确保所需字段真的进入 OIDC UserInfo 或 ID Token；启用 Back-Channel Logout 并将 URL 配置到可由 Casdoor 服务端访问的 SkillHub 公网地址；确认 Redis 可用及 session namespace 在各实例间一致。应用/迁移部署时还需确认 `V127` 已由 Flyway 执行。

## 7. 测试与验收

本次实现增加/更新了以下测试：

- `CasdoorSessionLogoutTest`：Logout Token 校验、撤销时间及浏览器退出相关逻辑。
- `OAuth2AuthorizationRequestResolverTest`：Casdoor 静默请求、`prompt=none` 与 PKCE 行为。
- `OAuth2LoginHandlersTest`：认证成功/失败处理和会话行为。
- `IdentityBindingServiceTest`：首次绑定、已绑定用户的员工资料同步与更新行为。
- `UserProfileControllerTest`、`profile.test.ts`：资料 API 与界面字段。
- `casdoor-session.test.ts`：自动探测、跳过条件和失败回退。

**尚未完成的验收：**此前最近一次真实公司账号独立复测虽然回到了 HTTP 首页，但没有观察到真实 `/api/v1/auth/me` 返回已登录用户，因此不能据此宣称“已登录 Casdoor 用户打开 SkillHub 自动建立 SkillHub 会话”已经端到端通过。模拟 OAuth 响应只证明前端/回调协议处理，不证明真实 token 换取、Casdoor 声明释放、Back-Channel Logout 可达或员工属性映射与公司配置一致。

建议部署验收按以下顺序逐项确认：

1. 用已登录 Casdoor 的浏览器新开标签页直接访问 SkillHub 根页面，确认最终用户资料 API 返回本人，而不是只检查首页加载成功。
2. 在 SkillHub 退出，确认浏览器被引导到 Casdoor 的 end-session endpoint，再访问其他 Casdoor 应用验证 SSO 会话状态。
3. 另一个浏览器会话登录 SkillHub 后，在 Casdoor 执行全局退出；确认 Casdoor 日志中 Back-Channel Logout 成功，再请求 SkillHub 受保护 API，预期失去认证。
4. 修改 Casdoor 员工属性后重新登录，确认资料 API/个人资料页显示新值；另确认 Casdoor 未释放或释放空值时 SkillHub 保留旧字段值。
5. 确认失败、未登录或 Casdoor 不可用时 SkillHub 页面仍可打开并保留手动登录路径。

真实账号测试时不要记录授权码、state、Cookie、ID Token 或 client secret；只记录 URL scheme/path、参数是否存在、响应状态及脱敏用户结果。

## 8. 实现边界与后续建议

- 自动登录依赖浏览器能访问 Casdoor，且 Casdoor 同站策略/浏览器隐私设置允许已有 IdP 会话用于静默授权；这不是跨站共享会话 Cookie。
- Casdoor 退出联动依赖 Back-Channel Logout 的服务端配置与网络可达；前端轮询不是 Casdoor 全局注销的替代品。
- SkillHub 主动注销是否能清除 Casdoor 全局会话，取决于 Casdoor 发布 `end_session_endpoint`、接受 `id_token_hint`/`client_id` 并允许回跳 URI。
- 员工字段 claim 名称须与实际 Casdoor 应用配置核对，特别是公司 `affiliation`、工号 `properties.job_number`、岗位 `properties.job_type`。
- 下一步优先补齐一套使用专用 Casdoor 测试账号和隔离 SkillHub 环境的真实 E2E/Smoke，把两个方向的注销、静默成功/失败、员工字段更新和数据库最终状态作为验收断言。
