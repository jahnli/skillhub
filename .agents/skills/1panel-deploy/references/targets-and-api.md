# SkillHub 1Panel 目标与执行契约

## 本地配置

根目录 `.1panel.local.json` 必须通过 `git check-ignore` 且没有被 Git 跟踪；当前 `*.local.json` 忽略规则可覆盖它。文件缺失时只报告缺项，不创建凭据或借用参考项目配置。

顶层为 `targets` 对象，键是用户环境名。每个目标的字段：

| 字段 | 要求 |
| --- | --- |
| baseUrl | 明确的面板 HTTP(S) API 基址，包含实际版本前缀，例如 /api/v2；不从应用地址推算 |
| apiKey | 非空字符串，只在请求进程内读取 |
| apiVersion / authAlgorithm | 实际版本与该版本支持的鉴权算法；执行时核对目标 API 文档 |
| node | 节点，缺省 local |
| composeName | 唯一编排名称，必须从面板查询精确匹配 |
| services | 下表服务映射；使用目标实际名称 |
| healthUrl | 后端健康检查完整 URL；不从面板地址推算 |
| accessUrl | 用户访问的完整入口，含实际子路径；不从 healthUrl 推算 |
| defaultTarget | 可选，位于顶层，仅在用户未指定时采用已配置且唯一的目标 |

`services` 的键是实际 Compose 服务名，值为 `component`（server/web/scanner/github-sync）及可选 `imageVariable`（镜像仓库变量名）。服务映射必须对照实际编排；参考 `compose.release.yml` 的对应关系是：

| Compose 服务 | 组件 | 默认仓库变量 |
| --- | --- | --- |
| server | server | SKILLHUB_SERVER_IMAGE |
| storage-permissions | server | SKILLHUB_SERVER_IMAGE |
| web | web | SKILLHUB_WEB_IMAGE |
| skill-scanner | scanner | SKILLHUB_SCANNER_IMAGE |
| github-sync | github-sync | SKILLHUB_GITHUB_SYNC_IMAGE |

`storage-permissions` 是一次性任务，成功应为 exited/0；不能要求它持续 running。`github-sync` 是可选 profile，只有目标已经启用或用户要求才部署并纳入运行验收。项目还依赖数据库、Redis 和配置的对象存储，不为应用升级改动这些服务。

组件镜像映射由调用参数提供，包含完整仓库、不可变 digest 或明确版本标签。配置本身不保存 API 令牌以外的运行日志。不要输出完整文件、API Key、签名、Compose/env 或含密码的响应。请求体留内存，使用结构化 JSON 和 UTF-8。

## 目标版本的 API

执行时先使用目标版本的官方 API 文档或可用连接器确认契约。以下 v2 路由来自参考流程，作为核对清单，不能证明任意 1Panel 版本均兼容；不支持时停止并报告实际差异，不循环尝试不同鉴权或猜测字段。

每次请求生成当前 Unix 秒级时间戳，使用 `1Panel-Timestamp`、`1Panel-Token`、`CurrentNode` 及 JSON Content-Type。MD5 兼容方式为 `hex(md5('1panel' + apiKey + timestamp))`；仅当目标文档明确支持时选用相应 HMAC 方案，不凭参考文件推断支持。鉴权失败检查开关、IP 白名单、时钟及配置，不降低面板安全设置。

核对以下 POST 路由（追加到 baseUrl，不重复 API 版本前缀），要求 HTTP 成功及业务响应成功：

- `/containers/compose/search`：分页查询，精确匹配 composeName，保留 path、createdBy、env。
- `/containers/inspect`：按 type=compose、实际 name/path 获取编排；按 type=container、实际容器标识取得 Id、Image、Config.Image、State 和 RestartCount。data 为 JSON 字符串时先解析。
- `/containers/compose/update`：核对 taskID、name、path、detailPath、content、createdBy、env、forcePull 的当前字段契约。原配置原样保留，forcePull=true，仅提交一次。
- `/logs/tasks/read`：按 taskID 查询并按 totalLines 分页读取必要任务日志，确认任务状态、拉取及重建结果。

多文件编排确认实际 detailPath，不能把逗号拼接路径当单文件。目标配置有并发变化迹象或期间中断时重新读取，发现变动停止，避免覆盖别人操作。

提交前须从目标版本文档或实际实现核实 compose/update 的拉取、重建、profile 和依赖范围；上列字段本身不提供服务级限制的证明，也不能凭空添加 services 字段。若更新会重启数据库、Redis、无关应用或启用停用 profile，且超出已有授权，停止并报告；可提出支持服务级选择的实际 API 或已明确授权的 SSH 路径，但不能擅自改用。无法核实影响范围时同样不提交。部署 SkillHub 应用不能默认授权整栈强制重建。

## 镜像与环境变量

参考 Compose 使用 `${SKILLHUB_SERVER_IMAGE}:${SKILLHUB_VERSION}` 一类表达式，image 变量仅表示仓库，版本单独拼接。不得把完整带 tag 的引用直接放入 imageVariable 后再追加版本，造成双 tag。

- 共用同一标签时，可按已核实映射更新仓库变量及 SKILLHUB_VERSION；先确认所有引用此变量的服务均属于本次范围。
- 混用版本、digest、不同变量结构时，针对具体服务修改 image 表达式或其专用变量；不做全局字符串替换、不把 digest 转成 tag。
- 若共享变量影响范围外服务，采用服务级更新或询问具体兼容决策，不能悄悄把整个栈升级。
- 保留 YAML 锚点、profiles、原 env 及 Unicode；变更后在内存中解析并检查有效引用。原配置含 secret，不将渲染的完整配置写入日志或临时文件。

## 等待与验收

每 5–10 秒轮询同一 taskID，单次等待不超过 30 秒，每 60 秒内通报，默认最多 15 分钟。Success 与必要的拉取/启动日志均须核实；超时保留 ID，查状态后再决策，不重复更新。

任务成功后容器仍为 starting 时，继续只读轮询运行/健康和 HTTP 就绪，默认最多 5 分钟，单次等待不超过 30 秒；正常启动中的状态不立即判失败。持续 unhealthy、退出非零或可确认的启动错误应及时报告，超时记为就绪核验受阻，不重新更新。

按服务语义验收：

- 常驻应用容器 running；有 healthcheck 时 healthy，缺少时明确注明。要求强制重建的常驻容器核对新旧 ID；记录实际运行 digest，同标签 digest 可以不变。
- 一次性 storage-permissions 按本次依赖执行需要核对 exited/0，未要求执行时不能因它未重建而误报失败。
- 后端参考 `/actuator/health` 的 JSON `status=UP`，Web 容器参考 `/nginx-health`，scanner 参考 `/health`，已启用 github-sync 参考 `/health/ready`；实际地址以目标配置、网络和反向代理为准。不假定外部可访问容器内部端口。
- 不使用 ai-gateway 的 `/api/status` 或 `success/data.version` 判定。版本字段缺失时使用运行镜像与构建 SHA/digest 证明，不虚构应用版本。
- 公网入口要核对真实 SkillHub 页面和实际子路径，避免将登录页/代理错误页的 HTTP 200 当作成功。
- 业务 Smoke 按项目测试规范隔离账号和数据。未执行时注明；不擅自发布/删除真实 skill 来测试。数据库回退与应用镜像回退分别评估，不自动 rollback。
