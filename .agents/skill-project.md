# 项目技能配置

本文件只保存当前仓库差异，六个通用技能的 SKILL.md 可独立复制到其他项目。复制后按目标仓库更新本文件，不能沿用原项目远端或部署适配。用户本次明确要求优先；本文件不授权执行提交、推送或部署，也不存凭据。下列路径均相对目标仓库根目录。

```yaml
project_name: SkillHub
git:
  push_after_commit: true
  target_branch: current
  push_targets:
  - remote: github
    expected_url: https://github.com/jahnli/skillhub
  - remote: gitlab
    expected_url: https://gitlab.semi-tech.com/stc/skillhub
  read_only_remotes:
  - upstream
changelog:
  mode: local-customizations
  path: changes/CHANGELOG.md
  details_dir: changes/details
  language: zh-CN
  stage_after_update: true
announcement:
  title: SkillHub 功能更新公告
  audience: 平台使用者
  featured_topics: []
main_upstream_deploy:
  workflow: .agents/skills/main-upstream-deploy/references/project-workflow.md
```

## 提交与日志约定

保留本项目原有约定：“提交”默认含推送，明确要求仅本地或限定远端时遵循用户要求。上表 github/gitlab 接收同一次提交；操作前须核对实际 push URL。upstream 即使存在也仅作只读来源。日志是有效二开清单，不是已发布版本历史；显式更新日志后沿用原有暂存约定，但不得混入用户无关或部分暂存内容。

## 工程约定

先读[工程入口](../.trellis/spec/index.md)，按改动选择领域、验证与部署契约。技能只组织流程，不另存工程规则。公告以技能注册中心的实际行为与权限为依据，不套用模型调价业务。

## main 发布适配

沿用[本项目适配](skills/main-upstream-deploy/references/project-workflow.md)：隔离合并、按影响验证、推送、发布多组件镜像，再通过 SSH 更新并核验版本与健康。SSH 字段及服务映射继续以原 [SSH 契约](skills/main-upstream-deploy/references/ssh-target.md)为准。未配置本地目标时报告缺项，不沿用其他仓库地址或面板配置。
