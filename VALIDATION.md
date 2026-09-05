# v0.2.0 验证记录

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
