---
name: git-commit-push
description: 使用英文约定式提交信息提交并推送 SkillHub 改动，按实际远端配置核验目标，并在明确的 GitLab 提交信息校验失败时使用受限回退。
---

# SkillHub Git 提交与推送

读取[工作流](../../../.trellis/spec/workflow/index.md)、[贡献指南](../../../CONTRIBUTING.md)和[测试入口](../../../.trellis/spec/testing/index.md)。用户要求提交时沿用本技能既有约定：除非明确仅本地提交或不要推送，否则包含推送；只要求审查、写提交信息或创建技能时不执行 Git 写操作。

## 提交范围

检查仓库根目录、当前分支、`git status --short --untracked-files=all`、已暂存和未暂存差异。单个逻辑变化一个提交；多个仓库分别处理，不跨仓库套用 SkillHub 目标。

只暂存本次明确范围，保留用户已有修改；混合文件使用可审查的局部暂存。已有无关 staged 内容时，选择只提交本次路径的安全方式或先说明无法可靠分离的具体阻塞，不将其一并提交。不要默认 `git add -A`。

提交信息标题、正文及说明使用英文，格式为 `<type>(<scope>): <description>`，简洁描述实际行为。按贡献指南使用现有用户身份 `git commit -s`，不代填或修改作者身份；提交身份缺失时报告缺项。提交前执行适用检查并报告环境限制，不擅自跳过相关失败或用旧结果代替。

## 远端选择

本技能适配时核实的配置只有 `origin`，URL 为 `https://gitlab.semi-tech.com/stc/skillhub`，当前工作分支为 `v1`。这些是现状记录，不是永久强制目标；每次重新读取 remote、push URL、branch upstream 和 refspec。

- 用户指定目标优先；否则沿用当前分支已配置的推送目标。未配置 tracking 时，若已确认唯一 fork 远端为 origin，可使用同名当前分支，并在操作前说明。
- 多个候选目标无法可靠区分时询问；不从 README 推断远端，不自动添加 GitHub remote，不套用 ai-gateway 的双远端发布。
- 仅当用户已指定或当前会话明确授权多个远端时，将同一提交分别推送。排除仅拉取的上游；不能遍历所有 remote。
- detached HEAD、远端地址与预期不符或目标分支不明确时先调查，不能猜 main/v1 或变更 Git 配置。

## 提交与核验

完成暂存和审查后创建提交，记录 SHA。远端推送使用明确 refspec，例如经核实目标为 origin/v1 时执行 `git push origin HEAD:refs/heads/v1`。不要自动 amend、rebase、`--no-verify`、强推或修改 hooks。

普通推送明确被 GitLab 的提交信息校验拒绝时，读取 [git-push-skip-lint](../git-push-skip-lint/SKILL.md)，在其适用条件下用同一目标重试一次；认证、保护分支、non-fast-forward、测试及扫描错误不适用。

每个推送目标用 `git ls-remote --heads <remote> <完整目标ref>` 核对实际 tip 等于记录的提交；本地 tracking ref 和“Everything up-to-date”不足以代替。一个目标失败时保留本地提交及已成功结果，报告失败和未执行目标，不自动回滚或继续扩展范围。

检查最终状态和 `git log -1 --oneline`，交付提交 SHA、标题、仓库、实际 remote/分支、推送核验结果及剩余修改。提交推送不自动触发部署工作；若仓库工作流会因 push 触发，按实际配置说明影响。
