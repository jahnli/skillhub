# main 流程的 SSH 目标

这是本技能新定义的本地配置契约；当前仓库没有预设 SSH 目标。根目录 `.main-upstream-deploy.local.json` 必须被 Git 忽略且没有被跟踪，使用时在进程内读取，不输出完整配置。

## 配置字段

| 对象 | 字段与含义 |
| --- | --- |
| git | forkUrl：可推送 fork；targetBranch：固定 main；upstreamUrl、upstreamBranch：已确认只读来源 |
| publish | githubRepo：实际 GitHub owner/repo；workflow：docker-publish.yml 或 publish-images.yml；ref：main |
| ssh | host、port（默认22）、username、directory（绝对部署目录）；identityFile 可选，或使用已有 SSH agent |
| ssh 主机身份 | knownHostsFile 或明确的 hostKeySha256，连接时严格匹配，不使用 AutoAddPolicy 或 StrictHostKeyChecking=no |
| ssh Compose | composeFiles：相对 directory 的文件列表；envFile：现有环境文件路径；projectName：实际项目名；profiles：当前已启用 profile 列表 |
| ssh 服务 | services：本次应用服务到组件/镜像仓库变量的映射，参照下文；healthUrl、accessUrl：完整实际地址 |

配置只接受必要的数据字段，不将任意 `commands` 字符串当 shell 脚本执行。缺少认证或可信主机身份时报告所需配置，不索取或打印私钥内容，不把密码放在命令行。首次信任由已确认的指纹建立，指纹变化停止。

服务映射与当前 `compose.release.yml` 的对应关系见 [应用服务表](../../1panel-deploy/references/targets-and-api.md)。SSH 实例的实际 Compose 为执行依据；若采用不同变量结构则先核实。services 包含实际要更新的常驻服务及使用同组件镜像的一次性 helper；github-sync 只在原已启用时更新。

## 部署步骤

1. 连接后确认 directory、项目名、Compose 文件、envFile、profiles 和现有容器属于目标实例，获取配置所需信息时不输出 secret。读取配置并在内存中渲染，确认当前应用镜像、卷、公开地址与配置。不能在不存在的目录执行命令或回退到默认 Compose 项目。
2. 比较原/新版本的迁移与配置要求。server 启动会执行 Flyway；需要迁移时先确认数据库与对象存储备份、兼容/恢复条件。不通过删除卷、初始化数据库或 Flyway repair 解决失败。
3. 使用本次构建已核实的完整镜像更新目标环境文件中的镜像键/版本，或针对服务的 image 表达式；只修改这些键，保留全部其他内容、权限和换行。写前核对原内容未并发变化，在远端原目录原子替换。原配置若需保存备份，留在远端受限位置并说明路径，不传回本地或记录到日志。
4. 共用标签时，仓库变量不包含 tag，版本写入 SKILLHUB_VERSION；需 digest 或混合版本时用精确的服务级表达式，避免双 tag。共享变量还影响范围外服务时不可直接修改；先确定服务级方案。不能只用一次性的进程环境覆盖而让后续重启恢复旧镜像。
5. 固定同一套 project/文件/env/profile 参数，分别执行拉取和重建；下列为参数结构，不是可原样执行的命令：

   ```text
   docker compose --project-name <project> --env-file <envFile> -f <composeFile> [其他文件及已启用profile参数] pull <本次应用服务>
   docker compose <同一参数> up -d --force-recreate <本次应用服务>
   docker compose <同一参数> ps
   ```

6. 执行前核实依赖：不对数据库/Redis或无关应用强制重建；仅在依赖已经就绪且 helper 不需重跑时使用 `--no-deps`，否则按已核实依赖图执行必要一次性任务及启动。不使用 down、down -v、prune 或重新启用停用 profile。
7. 参数逐个安全传递，远端路径和配置值不拼成未转义 shell。先拉取成功再重建；任一失败立即停止后续写操作。网络断开或结果不明先查远端配置及容器，不自动重发 up。每条命令记录退出码及必要脱敏输出。
8. 对照目标镜像/digest 和构建 SHA 核实运行版本，常驻容器 running/healthy，storage-permissions 按需 exited/0；再查 healthUrl（实际后端 status=UP）及 accessUrl 的 SkillHub 页面。沿用面板参考中的服务级健康语义，但不发 1Panel API 请求。

命令成功后仍在 starting 的服务允许最多 5 分钟只读就绪轮询，单次等待不超过 30 秒，每 60 秒内更新进度。启动错误及时报告；超时说明核验受阻，不重新执行重建。

配置已经写入而拉取/重建失败时，明确报告“配置已更新、运行未完成”及当前运行状态。自动恢复配置或回退镜像也属于写操作，不能用它掩盖失败或默认回退数据库。恢复需符合用户已有授权和实际 schema 兼容性。
