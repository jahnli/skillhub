---
name: git-push-skip-lint
description: SkillHub 向 GitLab 普通推送明确因提交信息格式或 commitlint 被拒绝时，保持同一远端和 refspec，使用 skip-lint 推送选项重试一次。
---

# GitLab 提交信息校验回退

仅处理已经授权的推送。先执行普通推送，读取完整错误；必须同时确认实际目标为 GitLab，且拒绝原因是提交信息格式或 commitlint。`pre-receive hook declined`、`push rule` 或一般 `lint failed` 本身不足以证明原因。

当前项目的 GitLab remote 为 `gitlab`，指向 `gitlab.semi-tech.com/stc/skillhub`；`github` 是另一个推送目标。每次以实际失败命令及 remote URL 为准，不能根据共享的 `origin/*` tracking ref 猜测 remote，也不改用不存在的 `origin`。

## 重试边界

- 提交信息格式错误且远端支持该 push option 时，保持同一 remote、源提交和完整 refspec，加 `-o skip-lint` 重试一次。例如普通命令为 `git push gitlab HEAD:refs/heads/v1`，回退为 `git push -o skip-lint gitlab HEAD:refs/heads/v1`。
- 远端不支持或无法核实支持时报告限制，不把选项当成通用 GitLab 能力。错误输出明确提示支持时可据此重试。
- 认证、权限、网络、分支保护、non-fast-forward、测试、构建或安全扫描失败均不适用。不绕过本地 hooks，不修改 Git 配置，不 amend、rebase 或强推。
- 重试失败后停止；结果不明先查询远端 tip，不重复发起。成功后用 `git ls-remote --heads <remote> <完整目标ref>` 对照原目标提交，再返回原工作流完成其余已授权目标。

交付说明普通推送的拒绝原因、是否重试、实际 remote/ref、远端最终提交和剩余事项。不得将部分同步报告为全部完成。
