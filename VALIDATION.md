# Memoh Android v0.2.15 验证记录（2026-09-10）

- 原因：旧版 MessageAttachments 只绘制附件图标和名称，没有加载图片和点击预览。实际机器人回复的 PNG 使用 content_hash 和 bot_id；受保护媒体接口实测返回 HTTP 200 / image/png / 75,732 字节，与附件记录一致。验证未保存真实图片或聊天正文。
- 新增图片媒体读取、登录刷新与权限错误处理、缩略图、全屏缩放和重试。解码在后台，最多 2 个并发、24 MB 下载和 24 MB 内存缓存，单张解码最多 400 万像素；无磁盘图片缓存。旧会话中的已有图片附件也使用此路径。
- 12 项 API 36.1 设备回归通过：图片显示、点击预览、双击放大及还原、实际缩放像素、关闭、失败重试、Base64 字节完整和大图解码边界；以及 5 项长会话、4 项表格回归。
- 已检查合成测试图片的缩略图和全屏截图。测试发现 Compose 1.7 全屏对话框会按含系统栏的屏幕高度测量，导致底部提示被裁切；改为按实际窗口测量后验证通过。截图和日志位于 `memoh-migration-backup/android-v0.2.15`。
- 82 项 JVM 测试通过；Debug/Release Lint、R8 和发布构建通过。沿用原签名，在模拟器覆盖安装成功，冷启动 Status ok / 3,393 ms；核实 versionCode 19 / versionName 0.2.15。真机当前未连接。GIF 仅预览静态帧，SVG 和 Markdown 内远程图片暂不支持。
- APK SHA-256：`ba191ac6905a8a2e23223995519c9f809e5ad35a20a32f10f4478fc3893b30e0`。证书 SHA-256：`d04126dd57f4764a295735b43120550f0d13651d439aacd2d2433b81060f047a`。

## v0.2.14（2026-09-10）

- 79 项 JVM 测试通过，包括 100 次流式增量合并后正文完整、最终状态及时发布、长内容跨页字符完整和消息块键稳定。
- 18 项 API 36.1 设备测试通过：1,000 个工具步骤加 20 万字思考的懒加载；展开后分页；上翻阅读期间持续输出不移动原位置；连续新增段落后底部屏幕坐标保持在 1 像素误差内；90 行表格每次只创建 31 行（含表头），可切到最后一页；原有表格、补充与会话删除测试。
- 对照恢复旧版 ChatScreen 执行同一大历史回归：旧版创建 200 个卡片，断言失败；修复版创建 14 个。没有进行真机帧率基准测试。
- 已查看流式底部、表格分页和原有会话截图；单组件测试截图不包含正常应用外壳的系统栏留白。
- 日志、旧版对照、截图和产物在 `memoh-migration-backup/android-v0.2.14`。
- Debug/Release 构建、R8、Lint 和发布签名校验通过；使用原签名在模拟器覆盖安装并冷启动成功，核实 versionCode 18 / versionName 0.2.14。用户手机未连接，尚未在真机实际 DeepSeek 会话上验收流畅度。

## v0.2.13（2026-09-09）

- 原因：旧聊天渲染器仅使用 Markwon core，未启用 GFM 表格解析。新版按完整 CommonMark 文档解析，以原生 TableLayout 展示单元格，并用 Compose 提供局部横向滚动；列表、引用、代码块和引用式链接保持文档语义。
- 76 项 JVM 单元测试通过；Debug/Release 构建、R8 和 Lint 检查通过（0 errors，现有及方向对齐等建议性 warnings）。
- API 36.1 上 4 项 MarkdownTableTest 全部通过：320 dp 下长中文自动换行且无裁切；同一行单元格等高；宽表格滑到最后一列且新行生成后保持位置；表头流式成型；列表／引用内表格；转义管道、空单元格、加粗、代码、换行、引用式链接及非 HTTP(S) 链接不可点击。
- 已查看浅色、深色、横向滑动后和嵌套表格截图，位于发布目录 android-v0.2.13/screenshots。截图来自应用 Compose 表面。首次设备启动因模拟器资源紧张失败；关闭构建守护进程，并以 4 核／3 GB 内存重新冷启动模拟器后，设备测试用时 7.657 秒且全部通过。
- 解析测试也发现引用链接定义会产生空白正文区块，已过滤空渲染结果，避免表格后多余留白。签名沿用既有发布证书。

## v0.2.11 验证记录（2026-09-08）

- 图片：检查确认线上近期上传的 JPEG 已成功持久化；原模型配置全部缺少 vision，原生运行时把图片降级为文件引用。用不含用户内容的红蓝色块 PNG 调用现有模型服务，14 个模型返回正确的上下颜色。已为这 14 个模型增加 vision，保留其余配置。gpt-5.4 与 gpt-5.4-mini 的上游返回 model_not_found，未修改。
- 配置备份位于 /root/migration-backups/vision-20260908/verified-model-config-before.jsonl；验证明细见 vision-probe-final.log、vision-probe-more.log、vision-deployment.log。探测首次使用 Python 默认 User-Agent 被上游 HTTP 403 拦截；改用 Memoh Go 客户端的 User-Agent 后验证成功。
- Go 完整测试：144 个包通过；全量 golangci-lint 通过。session、view、message、application 四组 race 测试通过。插入位置在队列消费时记录，不会被后续输出或终态快照改变。
- SQL 由 sqlc 1.31.1 重新生成，历史查询读取现有 run_id 列，无数据库结构迁移。Swagger 及 SDK 已生成，SDK TypeScript 检查通过。
- 安卓：76 个 JVM 单元测试通过。覆盖多条同批插入、待处理/拒绝/未知状态不冒充已插入、背景回执、旧回执防降级、同一任务跨轮次历史合并、批量与重复文本的本地记录清理。
- 初轮 22 项 Android API 36.1 设备测试通过：SteeringUiTest、SteeringLifecycleTest、AttachmentReaderTest、ComposerLifecycleTest。最终补充运行图片从系统 URI 到 WebSocket 的完整字节检查及更新后的聊天 UI 测试。
- 服务端已于 2026-09-08 04:00:35 UTC 部署到 memoh.minq.icu。部署前确认无活动任务，只重建 server。镜像 memohai/server:0.19.0-remote-acp-supplement1，Docker healthy，公网 /health 返回 ok。
- 服务器二进制 SHA256 a296efc964dcdc4ba46b582db4318624d4e1f889984005bedf7f71f2ba9130bb。桥接程序 SHA256 保持 7cc989d25cc01406085bb314e621051b022bf5c797e4f9bef45de0bea64e949b。回滚 Compose 位于 /root/migration-backups/supplement-20260908T040035Z/docker-compose.yml。

队列提示在模型确认消费后自动消失；补充内容保留在主对话。未确认或拒绝的内容仍可取回，客户端不会在重连时自动重发。完成后只有历史包含对应消息，才清理加密保存的已插入记录。

最终 5 项设备测试全部通过（4 项 UI、1 项真实图片 URI 到 WebSocket 字节检查），合计覆盖 23 个不同设备用例。已查看自动化生成的运行中与完成后的截图，补充内容均在主对话中，队列提示消失。这里的截图检查由编码代理完成，尚无用户端人工确认。

Release 构建、Debug/Release lint、R8 及原签名验证通过。证书 SHA256 d04126dd57f4764a295735b43120550f0d13651d439aacd2d2433b81060f047a。APK SHA256 f24f239831706c2b69ca2739c6dca3002299f7c6980de9598a8dbc800f2ed51f。

已在 API 36.1 模拟器上使用 adb install -r 覆盖安装，保留数据；冷启动 Status ok / TotalTime 3506 ms，确认 versionName 0.2.11、versionCode 15。
