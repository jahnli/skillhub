---
name: docker-publish
description: 为 SkillHub 触发已确认 GitHub 仓库的镜像发布工作流，跟踪同一次运行并核实各组件的实际标签、digest 和结果。用于构建发布 Docker 镜像。
---

# SkillHub 镜像发布

读取[部署规范](../../../.trellis/spec/deployment/index.md)、[部署手册](../../../docs/09-deployment.md)及实际工作流。创建、修改、解释技能不触发发布。执行发布请求授权一次工作流运行，不自动提交、推送、创建标签、启用工作流或部署。

## 确定仓库与发布路径

用户指定的 GitHub 仓库和 ref 优先；否则检查实际 remote 或已确认的会话配置。当前仅核实 GitLab `origin`，没有 GitHub 发布仓库默认值。找不到目标时询问 GitHub `owner/repo`，不自动使用 README 中的上游仓库，不修改 remote。

默认 Docker Hub 路径是 `docker-publish.yml`；明确要求 GHCR 时使用 `publish-images.yml`。操作远端前核对目标 ref 对应的远端工作流，不能用本地文件证明远端已支持该流程。

| 路径 | 本地已实现行为 |
| --- | --- |
| Docker Hub | 手动触发，可传 `reason`；四组件均发布 `latest` 和 `git describe --tags --always` 生成的同一版本标签；架构为 amd64/arm64 |
| GHCR | 手动或 Release published 触发；按 metadata-action 生成 tag/sha/semver，默认分支才有 edge；server/web 另支持 riscv64；可能镜像到已配置仓库 |

两者均包含 `server`、`web`、`scanner`、`github-sync`；最后一个构建上下文是 `github-sync-go/`。读取远端定义核实当前矩阵、输入、标签和触发副作用。Docker Hub 命名空间来自 secret，不能从 GitHub owner 推断。GHCR 不支持 `reason` 输入时不传。

未指定 ref 时查询目标仓库的默认分支；固定其 SHA 并报告。只发布远端版本，本地未推送修改不会进入产物。标签、同名分支存在歧义时解析完整 ref；触发前后分支变化导致 SHA 不一致时不交给部署。

## 触发和定位

优先可用 GitHub 连接器；无所需能力时使用 `gh`，始终显式指定仓库。示意命令中的参数必须替换为已核实值并按当前 shell 安全传递：

```text
gh run list --repo <owner/repo> --workflow <workflow.yml> --event workflow_dispatch --limit 30 --json databaseId,createdAt,headBranch,headSha,url,status,conclusion
gh workflow run <workflow.yml> --repo <owner/repo> --ref <ref>
gh run view <run-id> --repo <owner/repo> --json databaseId,url,status,conclusion,headBranch,headSha,event
```

记录触发前 UTC 时间、已有 ID、目标 ref/SHA；支持时使用触发响应的 ID。Docker Hub 可通过独立参数传 `--raw-field reason=<用户原因>`。只触发一次；请求超时或返回不明先查询，不再次提交。

按新增 ID、时间、workflow_dispatch、ref/SHA 匹配唯一运行，不能直接选最新一条。最多用约 30 秒定位；有多个候选或仍无法确定时报告归属未确认和链接，停止后续写操作。

## 等待与核验

持续轮询同一 ID，单次等待不超过 30 秒，每 60 秒内更新进度。默认等待到终态，最长 60 分钟；超时保留 ID 和状态，不取消或重跑。只有 `conclusion=success` 才进入完整产物核验，失败读取相关失败步骤并脱敏。

从本次运行的版本输出、矩阵任务、构建 push 记录和 registry manifest 核实每个组件的完整镜像引用、版本标签、digest、平台及 source SHA。不能从本地版本、日期或短 SHA 猜测版本标签，不以 `latest` 作为部署版本。

Docker Hub 四组件同版，但任一矩阵失败时可能已有部分镜像更新；明确报告部分产物，不自动继续部署。secret 脱敏导致仓库无法核实时，使用已确认配置或询问命名空间；不得展示 secret 值或将仅工作流成功称为镜像已全部核实。

交付仓库、远端 ref、实际 SHA、运行 ID/链接、四组件结果和核实的版本/digest；说明缺失证据。传给 [release](../release/SKILL.md) 时提供完整组件到镜像的映射，不简化为单一镜像。
