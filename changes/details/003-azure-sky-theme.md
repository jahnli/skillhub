# 统一 Azure Sky 主题与圆角

日期：2026-11-28

## 配色与亮暗模式

前端采用 AI Gateway 默认 Azure Sky 配色参考，通过现有 HSL 语义变量映射到 Tailwind 3。主要操作、链接、选中导航和焦点使用蓝色；亮色模式保留白色卡片与弹层，交互区域使用淡蓝色；暗色模式使用炭灰页面、卡片和弹层，并提亮主蓝色、配合深色主按钮文字。

首页、登录页、工作台、技能与套件详情、通知、快速开始示例和 GitHub 同步等界面中的相关固定颜色改用共享变量。发布、审核、安全扫描等已有状态继续沿用成功、警告、危险和信息的语义区分；信息态与主色统一，通知未读标记使用主色。卡片阴影、悬停表面、代码示例和消息提示随亮暗主题取色。

## 圆角与交互

- 表面基准 `--radius` 为 `0.75rem`（默认根字号下为 `12px`），卡片、弹窗、菜单和消息提示共享该值。
- `--radius-sm` 和 `--radius-md` 分别为基准值的 `0.6` 和 `0.8` 倍；普通按钮、输入框、选择器及文本框统一使用 `--control-radius`，其值引用 `--radius-md`，大号按钮也使用相同控件圆角。
- GitHub 同步、推荐编辑、发布编辑、我的技能等页面的相关独立圆角改为引用共享变量；标签、头像及贴边面板保留对应形状。
- 登录返回入口和首页操作补齐共享焦点样式；GitHub 同步和我的技能筛选输入使用主题焦点边框，非法输入使用危险态边框。
- 首页操作区和通知分类允许换行，以适应较窄视口。

该调整沿用现有 React 组件、控件尺寸和密度机制，未引入参考工程的主题定制功能或组件运行时。

## 主要相关文件

- `web/src/index.css`、`web/tailwind.config.ts`：亮暗语义色、圆角、阴影与 Tailwind 映射。
- `web/src/shared/ui/card.tsx`、`web/src/shared/ui/dialog.tsx`、`web/src/shared/ui/dropdown-menu.tsx`、`web/src/shared/components/toaster.tsx`：卡片、弹层及提示的表面和圆角。
- `web/src/app/layout.tsx`、`web/src/pages/home.tsx`、`web/src/pages/landing.tsx`、`web/src/pages/dashboard.tsx`：导航与主要入口配色。
- `web/src/pages/login.tsx`、`web/src/features/auth/auth-shell.tsx`、`web/src/features/auth/session-bootstrap-entry.tsx`：登录表面、操作和焦点。
- `web/src/shared/components/quick-start.tsx`、`web/src/shared/components/landing-quick-start.tsx`：随主题取色的命令示例和引导操作。
- `web/src/features/github-sync/`、`web/src/features/recommendation/`、`web/src/features/publish/presentation-editor.css`、`web/src/pages/dashboard/my-skills.module.css`：业务页面颜色、焦点及圆角映射。
- `web/src/features/security-audit/`、`web/src/features/skill/`、`web/src/features/suite/`、`web/src/pages/notifications.tsx`：状态与信息展示。
- `web/e2e/fixtures/ui-foundation.tsx`、`web/e2e/ui-foundation.spec.ts`：亮暗模式及两种密度下的圆角、主题变量传递与焦点断言；断言存在不代表本次日志维护已运行测试。
- `.trellis/spec/frontend/design-system.md`、`.trellis/spec/frontend/visual-foundations.md`：产品主题与组件基线的适用边界。
