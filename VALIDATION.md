# v0.2.6 验证记录

2026-09-05；工具详情每次只展开一个。

- 工具详情不再各自保存展开布尔值，改为会话页面持有一个工具标识并下传到历史／实时消息；标识包含所属轮次，优先使用 tool_call_id，缺省时使用块编号。核对切换工具、再次点击收起和会话隔离的状态路径。
- 沿用界面回归 `MobileShellTest` / `DecisionUiTest`，API 36.1 / Android 16 模拟器 12 项通过，0 失败／跳过；验证聊天、导航、选择器及批准／回答面板仍正常渲染和操作。
- `testDebugUnitTest` 58 项通过；`lintDebug` / `lintRelease` 均 0 errors、30 warnings，沿用既有建议。本次没有新增测试用例或改动 API 协议。
- Release 构建、R8／资源收缩及 v2 发布签名校验通过，沿用原证书；模拟器从 0.2.5 / 9 覆盖安装 0.2.6 / 10，冷启动 `Status: ok`。升级前已在登录页，不据此声称认证会话保留。
- 回归使用本机模拟数据，未向真实 Agent 发送请求。本轮未重跑上一版的全部 33 项设备回归，历史记录保留如下。

# v0.2.5 验证记录（上一版）

2026-09-05；批准、拒绝和交互回答，以及同一控制链路的状态恢复。

- Release 构建、R8／资源收缩、v2 发布签名校验通过，沿用原证书。模拟器从 0.2.4 / 8 覆盖安装 0.2.5 / 9，冷启动 `Status: ok`。升级前已在登录页，不据此声称认证会话保留。
- `testDebugUnitTest` 最终 58 项通过；新增能力字段省略／显式 false、队列与历史合并、未知选项种类、单选／多选／自定义／必填／跳过校验、已确认结果隔离、已结束／不可操作通知过滤，以及旧快照不能恢复已结束任务的回归。
- API 36.1 / Android 16 模拟器全套 33 项通过，0 失败／跳过。含既有登录、模型记忆、附件、通知和聊天回归；新增 4 项真实 TLS WebSocket + AppState 生命周期测试和 4 项 Compose 测试。初次新测试环境的地址构造线程与模拟服务端清理错误已修正，随后全套通过。
- 生命周期回归检查：批准只发一次、精确 option_id、拒绝原因、失败可重试、重新打开待批准会话、非本会话／类型回执忽略、正常的 applied=false 无 code 不误报、已确认结果抵抗 pending 快照、用户回答／取消协议、断线不重放、运行态先于回执解决请求后释放下一项，以及控制错误不导致聊天假断线。
- 最终界面与控制实现另在 320×600 dp 执行 8 项专项，全部通过；随后增加旧快照序号保护并通过最终单元测试。再显式打开系统软键盘，在 320×600 dp 实际窗口执行单选→自定义回答→滚动提交→取消专项 1 项通过，实际键盘截图为 `screenshots/narrow/user-input-ime.png`。键盘开启时表单可滚动到提交按钮，尺寸、密度和键盘设置已还原。
- `lintDebug` / `lintRelease` 均 0 errors、30 warnings：28 条既有建议，新增 2 条测试依赖更新建议。未将测试用 TLS 证书或服务端带入 Release。
- 对照本地官方 Memoh `67fe0e6` 的 `usePendingApprovals.ts`、`tool-approval-actions.vue`、`chat-user-input-form.vue`、`decisions.ts`、`useChat.types.ts`、`local_channel.go` 和 userinput 校验协议。只处理当前活动 turn 的待决定请求；服务器仍校验实际权限和有效性。
- 测试均使用本机模拟 REST／TLS WebSocket，未批准用户真实 Agent 的工具执行，未发送真实任务。不声称用户未提供的服务器日志／机型已经实测。回答草稿及拒绝原因不写入磁盘，离开会话或结束进程后需重新填写；已结束、过期或明确不可操作的请求只能查看。

# v0.2.4 验证记录（上一版）

2026-09-05；模型与思考强度跨应用进程保存。

- Release 构建、R8／资源收缩和 v2 发布签名校验通过，沿用原证书。模拟器从 0.2.3 / 7 直接覆盖安装 0.2.4 / 8，冷启动返回 `Status: ok`。升级前已在登录页，不据此声称认证会话保留。
- `lintDebug` / `lintRelease` 均为 0 errors、28 warnings：27 条既有建议，加 1 条 SharedPreferences KTX 建议。本次保留 `commit()` 的布尔返回值，以在写盘失败时提示，且确保选择确认前已完成持久化。
- 单元回归 50 项、API 36.1 / Android 16 模拟器全套 25 项通过，0 失败／跳过。新增 4 项生命周期回归，覆盖持久化恢复、账号／服务器／会话隔离、退出登录保留、跟随默认、模型下架回退、写入失败，以及直接 Agent 和 ACP 运行时重建后的恢复。
- 最终 ACP 恢复逻辑另执行 2 项专项通过。模拟服务端重置为默认模型后，客户端恢复已选模型及强度；PATCH 失败不假报成功，刷新重试待服务端确认后恢复可用。
- 另手动安装隔离的 Debug 和 instrumentation APK，分两次 `am instrument` 执行保存／读取：第一次通过实际 AppState 选择模型二和高强度，随后 `am force-stop` 并确认旧进程已不存在；新进程打开同一会话及刷新后仍恢复两项。两个阶段各 1 项通过，不依赖 SavedStateHandle 或内存容器。
- 本轮使用本机模拟 API，没有向真实 Agent 发送测试任务。升级后需要重新选择一次模型；旧版本已丢失的内存选择无法恢复。偏好只保存在当前安装的本机数据中，不跨设备同步。

# v0.2.3 验证记录（上一版）

2026-09-05；通知只保留左侧身份图标，顶部状态栏使用 Memoh 轮廓。

- Release 构建、R8／资源收缩和发布签名校验通过，沿用旧证书；模拟器从 0.2.2 / 6 覆盖安装 0.2.3 / 7，并对最终 APK 再次覆盖安装及冷启动成功。Lint Debug／Release 均 0 errors、27 warnings（22 条既有建议及 5 条兼容 PNG 图标外形建议）。
- 单元回归 50 项通过；本轮按通知范围执行设备专项，上一版 21 项全套回归保留在历史记录。

- 通知专项 `PendingServiceTest`：API 36.1 / Android 16 模拟器 2 项通过，0 失败／跳过。实际 NotificationManager 收到的等待、失败结果、超时结果和锁屏 publicVersion 均不含 largeIcon，smallIcon 指向新的 `ic_stat_memoh`，通知色为品牌紫色；通用文案、隐私可见性、过期监听与旧启动回归通过。
- 状态栏图标 alpha 轮廓与官方彩色 Memoh 矢量逐像素比较，差异低于 1%；系统 PackageManager 返回的应用图标保留官方两种紫色。五档密度 PNG 从现有官方矢量生成，作为非自适应图标读取方式的兼容资源。
- 核对实际通知中心截图：左侧显示彩色 Memoh 应用图标，右侧无第二个 logo。等待通知为低重要性，模拟器在该配置下不在顶部显示静默通知图标；不把图标资源核对表述为强制覆盖系统的静默图标设置。
- 本次测试使用本机模拟 API，不向真实 Agent 发送请求。截图中的错误状态为测试样例。用户尚未提供真机品牌／系统版本，未声称已在其系统上完成实机验证；图标缓存是否刷新需要在该设备覆盖升级后观察。
- 系统模板依据：[Android 通知设计](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)（状态栏单色图标、可选 large icon、厂商模板差异）；[小米通知 SDK 文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1544)与[华为通知设计](https://developer.huawei.com/consumer/cn/doc/doccenter-ux-design/system-features-notification-0000001793074217)说明系统可从应用图标取得通知身份标识。未加入厂商私有反射接口。

# v0.2.2 验证记录（上一版）

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
