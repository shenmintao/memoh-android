# v0.2.2 验证记录

2026-09-05；模型浮层、UUID 隐藏和实际思考强度参数。

- `lintDebug` / `lintRelease`：各 0 errors、22 warnings，沿用依赖／target API／KTX 建议。Release R8 与资源收缩、v2 签名校验通过，沿用原 RSA 4096 发布证书。
- API 36.1 模拟器从已安装的 0.2.1 / 5 直接覆盖安装 0.2.2 / 6 成功，冷启动 `Status: ok`。升级前已在登录页，本轮未据此宣称现有认证会话保留，登录表单可见字段前后相同。

- `testDebugUnitTest`：50 项通过。新增原生／直接 Agent／ACP 能力与默认值校正、UUID 文案回退；扩展官方 REST 响应解析与 reasoning PATCH 合约，以及实际 TLS WebSocket 的 reasoning_effort 请求参数。
- `connectedDebugAndroidTest`：API 36.1 / Android 16 模拟器最终全套 21 项通过，0 失败／跳过。包含 ACP 思考强度未确认时保留原值并阻止发送、刷新恢复、确认成功；强度按会话恢复且不泄漏到其他会话；既有附件、通知、登录和聊天回归。
- Compose 新增供应商分组、模型 UUID 不展示、强度勾选与按钮同步、不支持思考时隐藏入口、搜索无结果／清空恢复，以及旧设备／模型选择回归。
- 最终布局另在 320×600 dp 窄屏执行模型与设备 2 项专项回归，以及 960×800 dp 宽屏执行模型浮层 1 项专项回归，均通过。核对手机、窄屏、宽屏实际窗口截图，含搜索键盘：浮层收缩列表以保留按钮，宽屏右侧子菜单，手机同一浮层内返回。
- 源码依据本地官方 Memoh `67fe0e6`：`model-options.vue`、`reasoning-effort.ts`、`useAgentModelCatalog.ts`、`chat-pane.vue`、SDK 类型及 ACP Go handler。仅展示服务端支持的档位；供应商读取失败不阻断模型使用。
- 本轮请求验证使用模拟 REST / TLS WebSocket，未向用户真实 Agent 发送测试任务。截图模型与会话为测试数据。具体部署提供的档位取决于服务器能力。

# v0.2.1 验证记录（上一版）

2026-09-05；客户端更新，通知 logo、聊天附件上传、设备与模型选择。

## 自动回归

- `testDebugUnitTest`：46 项通过。新增官方模型／设备／ACP REST 合约、实际 TLS WebSocket 携带文件 data URL / MIME / 文件名 / model_id / workspace_target_id、仅发送附件和断线不重放、附件内存上限与会话隔离、设备在线／目录绑定规则、附件历史解析。
- `connectedDebugAndroidTest`：API 36.1 手机模拟器，19 项全部通过。新增真实 ContentResolver 二进制／空文件读取、超限拒绝、已删除文件读取失败；系统 OpenDocument 多选 Intent 及真实 MainActivity 返回回调；附件与模型／设备按会话恢复、迟到文件选择隔离；ACP 切换失败不假报成功、刷新和成功切换。
- Compose 回归验证附件卡片、移除／读取失败重试、无文字发送、模型搜索／选中、设备选择／离线不可选，以及既有登录记住、聊天空 error、导航和深色主题。
- 通知服务回归检查系统实际收到的等待／结果／锁屏通用通知，包含官方两种紫色的 large icon，仍保留单色 small icon、通用错误文案和可见性。通知栏截图使用模拟失败事件，截图中的错误提醒是测试样例。
- 320×600 dp 窄屏：附件与选择器 2 项回归通过；发现键盘遮挡弹窗按钮后，显式应用 IME insets、列表按剩余高度滚动、选中后关闭键盘，最终模型／设备选择器专项复测 1 项通过，并核对截图中搜索时“刷新／完成”可见。相关截图为 `screenshots/ime-final/`。
- `lintDebug` / `lintRelease`：各 0 errors、22 warnings（沿用依赖／target API／KTX 建议及测试 SDK 版本条件建议）。
- 最终 R8 / 资源收缩 / Release 构建与签名验证通过，包名 `icu.minq.memoh`、versionCode 5，沿用旧发布证书。在 API36.1 模拟器上从 0.2.0 / 4 直接 `adb install -r` 升级至 0.2.1 / 5，冷启动返回 `Status: ok`。

## 范围

- 接口依据本地官方 Memoh `67fe0e6` 的 `useComposerAttachments.ts`、`useChat.ws.ts`、`useAgentModelCatalog.ts`、`chat-pane.vue`、`workspace-target.ts` 和 SDK / Go handlers 核对。
- 文件作为聊天附件随消息提交，无独立 HTTP 上传进度百分比；客户端显示读取、就绪、读取失败、提交和等待回复状态。最多 10 个文件、合计 8 MB；附件及选择仅在进程内保存。
- 已绑定工作目录的会话不覆盖 `workspace_target_id`，Agent 会话由 Agent 配置决定设备。列表依赖服务器 API 与账户权限，无法读取时明确提示并提供刷新。
- 本轮没有向用户真实 Agent 发送测试任务。网络与选择回归使用模拟 API / TLS WebSocket；不把这些结果表述为所有部署、模型文件理解能力或厂商后台策略均已验证。
- 文件管理器、文件夹上传、附件下载／图片预览、交互式 user-input、历史分页和多并发 pending 未包含在本轮。

# v0.2.0 验证记录（上一版）

2026-09-05；本次修改原生 Android 客户端，没有更新 Memoh 服务端。

## 结果

- `testDebugUnitTest`：41 项，0 失败、0 错误。包含成功终态 delta 携带空 error、error 缺省/null/空白、真实 errored/lost，以及原有认证、WebSocket 生命周期、pending、草稿和历史合并测试。
- `connectedDebugAndroidTest`：API 36.1 手机模拟器，13 项全部通过。新增 Compose 真实界面测试验证成功回复不出现错误、下一条发送、真实错误显示、导航搜索切换、登录表单恢复和取消记住、深色阅读布局。
- 加密存储设备回归验证：三个字段能通过新存储实例恢复、SharedPreferences 没有明文、重复加密使用不同 nonce、损坏密文清除、主动忘记后无法恢复。登录流程回归验证：只有成功登录保存、登出恢复表单、错误密码不覆盖原记录、不勾选不保存。
- 会话刷新设备回归验证：在 Chat 中刷新保持页面、会话和草稿；返回能取消迟到加载。
- 960×800 dp 平板模拟尺寸：5 项界面测试通过。
- 960×360 dp、150% 字体模拟尺寸：登录按钮可滚动访问，导航可滚动搜索并选择会话，2 项测试通过。
- `lintDebug` / `lintRelease`：0 errors，各 21 warnings（依赖更新、旧 target API、KTX 和 v26 资源目录建议）。
- R8 Release 构建、资源收缩与 APK 签名验证通过；RSA4096、v2、单一签名，证书与 v0.1.2 相同。

## 正式包升级

- 在原来安装 0.1.2 / versionCode 3 的模拟器上 `adb install -r` 覆盖到 0.2.0 / 4，没有卸载正式应用。
- 冷启动成功，旧登录状态保留并进入机器人页面，未发现正式应用的 crash 日志。
- 实际 Debug 主 Activity 打开软键盘，系统 `mInputShown=true`、`mIsInputViewShown=true`，登录表单和继续按钮可访问；截图为 `screenshots/login-ime-actual.png`。
- 另在开启模拟器软键盘显示后复测聊天输入/发送回调，1 项通过。
- 尺寸、密度、字体和硬件键盘对应的软键盘显示设置已还原。

## 范围

- 截图中的用户名、服务器域名和聊天内容均为测试样例，不是用户真实聊天。Compose 截图中的 keyboard 名称表示输入聚焦步骤；系统键盘的直接图像证据是 `login-ime-actual.png`。
- API 合约及真实服务器的读取/原账户恢复沿用原客户端。本次没有向真实 Agent 额外发送任务，不把 UI fixture 测试视为真实后台/服务端回复全链路验证。
- 没有覆盖 API26–35 真机、厂商省电策略或全部多窗口组合；低高度窗口由 `wm size/density` 模拟。
- 界面按官方 mobile shell 与登录页在 Compose 中重写，使用官方 SVG 路径和设计 token；未内嵌 Vue/WebView。文件、终端、浏览器、定时任务等网页管理面板不在本客户端已有功能范围内。附件、交互式 user-input、历史分页和多并发 pending 的限制继续保留。
- 不预置部署 URL、用户名或密码；记住功能为显式勾选，密码以 AndroidKeyStore AES/GCM 加密存储，未进入 SavedState、日志、通知或交付源码。
