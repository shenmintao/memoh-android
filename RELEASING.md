# 发布流程

## 验证

JDK 17、Android SDK 36。运行 `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`；连接设备后运行 `./gradlew connectedDebugAndroidTest`。CI 自动构建 Debug 与未签名 Release，设备测试需在模拟器/真机执行。

## 正式 APK

更新 `app/build.gradle.kts` 的 versionName 和递增 versionCode，再更新 CHANGELOG.md。保持 `applicationId=icu.minq.memoh` 和既有发布证书，才能覆盖安装。

正式签名在可信构建机完成，通过环境变量提供 `MEMOH_KEYSTORE_FILE`、`MEMOH_KEYSTORE_PASSWORD`、`MEMOH_KEY_ALIAS`、`MEMOH_KEY_PASSWORD`，运行 `./gradlew assembleRelease`。签名文件和密码不进入 Git。

用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查 APK，用 `sha256sum` 记录下载校验值。当前既有证书 SHA-256：`d04126dd57f4764a295735b43120550f0d13651d439aacd2d2433b81060f047a`。

创建版本标签和草稿 Release，上传已验证的签名 APK 与 SHA256SUMS；做一次覆盖安装、冷启动、登录、流式回复、审批和图片预览后发布。GitHub Actions 产出的未签名 APK 不能替代正式升级包。

配套 [服务端](https://github.com/shenmintao/memoh) 与 [Runtime 客户端](https://github.com/shenmintao/memoh-clients)。这是独立 Android 实现，没有可直接合并的 Android 上游；更新 Memoh 服务端后运行协议与设备回归。
