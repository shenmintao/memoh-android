# Memoh Android

当前发布版本：`0.2.0`（versionCode 4）。修复明细见 [CHANGELOG.md](CHANGELOG.md)。

原生 Kotlin / Jetpack Compose 的 Memoh 聊天客户端。应用**不内置任何服务器地址**。首次启动输入 HTTPS URL、用户名和密码；勾选“记住登录信息”后，登录成功时三项一起加密保存在本机，下次打开登录页自动填入。取消勾选会清除记住的信息。退出登录清除会话令牌，但保留用户主动选择记住的登录表单。

## 功能

- 依据官方 Memoh mobile shell 和登录页重写原生 Compose 界面：官方 SVG logo、点阵登录背景、黑白主要按钮、浅紫用户气泡、简洁阅读区、双行输入胶囊、机器人切换和会话搜索。引用版本和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- 按官方 768 dp 断点切换手机左侧导航抽屉／平板 288 dp 常驻侧栏；聊天阅读区最大 840 dp，空会话输入区最大 704 dp。登录表单保持单栏、居中限宽、可滚动，键盘弹出不切换组件分支。
- 登录后读取 `/users/me`、机器人、机器人设置、工作目录、会话和历史消息。
- 创建会话前选择工作目录；只有一个活动目录时自动选中，但仍可改为不绑定。
- 根据机器人设置创建默认 Agent / Pi（ACP 或直接运行时）会话：传递 `default_bot_agent_id`、运行时类型、项目元数据及工作目录。
- 每个聊天页面一个机器人级 OkHttp WebSocket，优先使用 `Authorization: Bearer` 请求头；仅当握手明确返回 401 时，刷新认证并进行一次 `?token=` 兼容回退。
- 支持断线重连、重新订阅、epoch/seq 缺口恢复、snapshot/delta、停止生成和工具批准/拒绝。消息只入队一次，断线后绝不自动重发；控制请求未确认时明确提示状态未知。
- 展示文本、可折叠思考／工具详情、错误、通知及未知块，支持复制正文和返回最新消息；阅读旧消息时不强制跳底。流式和最终文本均使用 Markwon，不使用 WebView；未引入图片插件，因而不会加载远程 Markdown 图片。
- 回复等待期间使用 `dataSync` 前台服务。服务使用第二条**只读**订阅监控完成状态，不发送或重发用户消息。
- Android 13+ 在用户从可见界面发送时请求通知权限。

## 架构

项目是单 `app` 模块，采用手工依赖容器：

- `network/MemohApi.kt`：可取消 REST、令牌单飞刷新、账户代次保护。临时网络错误不清除凭据。
- `network/ChatSocket.kt`：串行 WebSocket 状态机、单次发送、关闭握手、重连/订阅。
- `network/RuntimeReducer.kt`：权威快照和严格 epoch/seq 增量归并。
- `security/TokenStore.kt`：AndroidKeyStore AES/GCM 会话令牌。
- `security/EncryptedLoginStore.kt`：用户选择记住时，用独立 Keystore 密钥和随机 nonce 加密 URL、用户名和密码；不放入 SavedState、日志或通知。
- `model/RuntimeFeedback.kt`：空 error 字段不会生成错误提示，errored/lost 仍有失败回退文案。
- `ui/`：官方风格登录、适配手机／平板的导航、会话和消息组件。
- `data/AppState.kt`：Compose 可观察 UI 状态、导航加载所有权和历史竞态保护。
- `data/PendingOperationStore.kt`：服务与 UI 共享的账户/会话/invocation 状态。
- `data/DraftStore.kt`：按账户和会话保存的 ViewModel 内存草稿，成功入队后才清除。
- `service/PendingReplyService.kt`：仅在回复待定时存活的前台监控服务。

首版没有引入 Room，以缩小可工作的构建面。机器人、会话、历史和流式文本仅在内存中，进程死亡后会从服务器重新获取；仅待处理的账户哈希、`bot/session/invocation/run/turn` 标识、阶段及起始时间写入私有 SharedPreferences。令牌与正文不在 Intent 或该状态文件中。服务重建只恢复只读订阅，绝不自动重新发送 prompt。草稿跨同进程旋转/会话切换保留，不写 SavedState 或磁盘，不保证跨进程保存。

## 配置与构建

要求 JDK 17+、Android SDK Platform 36，最低系统 API 26，目标 API 35。

创建本地且已被 `.gitignore` 忽略的 `local.properties`：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

Windows：

```powershell
$env:ANDROID_SDK_ROOT='C:\path\to\Android\Sdk'
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug
# 连接 API 26+ 设备/模拟器后（会安装隔离 Debug/test APK）：
.\gradlew.bat connectedDebugAndroidTest
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 登录和 URL 规则

没有记住的登录信息时，登录页地址默认为空，只给出通用提示。输入可以省略 scheme，此时补为 HTTPS；末尾没有 `/api` 时补上，已有 `/api` 时不会重复。HTTP 明文地址被拒绝。只有登录成功后，规范化地址才与令牌一起加密保存；勾选“记住登录信息”还会将登录表单的 URL、用户名和密码加密保存至独立存储。未勾选则不保存密码，取消勾选立即清除之前记住的表单；失败登录不会用错误密码覆盖已记住的信息。

## 权限与通知

- `INTERNET`：REST / WebSocket。
- `POST_NOTIFICATIONS`：Android 13+ 的完成提醒；拒绝后系统仍可按平台规则运行前台服务，但抽屉中的完成通知无法保证。
- `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC`：用户点击“发送”后立即启动回复监控。

等待通知为低重要性、持续且不含消息正文，锁屏可见性为 secret。完成、错误或等待批准时发送通用通知，并深链回应用。没有常驻守护进程、开机广播、FCM、WakeLock。强制停止应用后 Android 不允许后台恢复；下次手动启动时只读核对待处理标识和服务器状态，不会重发 prompt。

监控有明确上限：待接纳 30 秒、连续离线 2 分钟、每次监听最长 30 分钟。超时保留 **UNKNOWN（结果未知）**，停止前台服务，不会误报远程执行失败。用户可“查看”“继续监听”，或先核对原会话再确认“解除等待”；解除本地等待不等于停止远程任务。没有无限自动恢复循环。多个不同审批 ID 分别通知，所有系统通知只用本地通用状态文案。

## 当前限制

- 用户输入请求会显示问题，但首版是只读的；工具批准/拒绝可操作。
- 成功入 OkHttp 队列不等于服务端已接纳。网络中断导致结果未知时必须核对原会话，客户端不会重放 prompt；监听暂停也不停止远程工作。
- 未实现附件、历史向前分页、会话删除/改名、编辑/重试和多并发待处理回复。
- WebSocket 始终先尝试 Authorization 请求头。部分代理/HTTP2 路径会错误拒绝该握手，因此仅在握手 401 后刷新令牌并回退一次 URL 查询参数。查询参数可能被 Memoh 当前请求日志、反向代理或基础设施 URL 日志记录；部署方应修复请求头路径并对 `token` 查询参数脱敏，此回退不会循环，也不会由客户端记录 URL。
- 深链通知携带 bot/session 标识，不携带令牌或消息正文；首版在进程已被杀死时会先回到登录/机器人页并正常恢复账户状态，而不会伪造发送恢复。

## 安全说明

- `allowBackup=false`，备份和设备迁移规则明确排除 root、sharedpref、file、database，包括加密登录记录。
- AndroidKeyStore 内生成 AES 密钥，AES/GCM 使用随机 nonce 和固定版本 AAD；密钥失效或密文损坏会清除登录状态。
- 密码只在用户勾选“记住登录信息”且登录成功后加密保存在本机；不把凭据放入 Room、Intent、SavedState、通知或日志。记住的信息与会话令牌使用不同的 AndroidKeyStore 密钥，退出登录不删除用户选择记住的表单。
- 禁止明文流量，只信任系统 CA；没有 trust-all、宽松主机名验证、自签 TLS 绕过或证书固定。
- OkHttp 不安装请求/响应 body 日志拦截器，发布构建不输出认证、查询或正文。
- 不使用 WebView；Markwon core 不启用 HTML 插件和远程图片插件，并在渲染前阻止危险 URI scheme。

## Release 签名

项目不会生成或保存 keystore。发布签名仅从环境变量读取：

- `MEMOH_KEYSTORE_FILE`
- `MEMOH_KEYSTORE_PASSWORD`
- `MEMOH_KEY_ALIAS`
- `MEMOH_KEY_PASSWORD`

示例：

```powershell
$env:MEMOH_KEYSTORE_FILE='D:\secure\memoh-release.jks'
# 在安全终端或 CI secret 中设置其余变量
.\gradlew.bat assembleRelease
```

APK 自签名与 HTTPS 证书是两件事；不要为了自签 APK 而降低 TLS 验证。发布者应离线备份同一签名密钥，并用 `apksigner verify --verbose --print-certs` 验证产物。

## API 假设

REST base 是用户输入地址规范化后的 `/api`。远程 ACP 工作目录会按部署方对 Memoh v0.19 的补丁原样提交，不由 Android 客户端分类拒绝。实现依据同版本 Memoh Web/server 合约：`POST /auth/login`、`POST /auth/refresh`、`GET /users/me`、`GET /bots`、bot settings/workdirs/sessions/messages，以及 `/bots/{bot}/web/ws`。WebSocket 处理 `run_accepted`、`run_rejected`、`error`、`control_ack`、`runtime_snapshot`、`runtime_delta` 和 `runtime_dropped`。快照是权威状态；epoch 变化、序列缺口或 dropped 会触发重新订阅。
