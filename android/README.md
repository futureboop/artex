# ARTEX Android

原生 Android 客户端，使用现有 ARTEX `/api/*` 接口，不要求改动服务器。首页优先读取任务列表；发现和对话在打开对应页面时加载，避免登录后同时请求大量数据。

![原生 Compose 首页预览（示例数据）](preview/home.png)

首版支持任务列表、主 Agent 会话查看与发送/停止消息、发现分页、已有独立对话、自定义相册背景和背景遮罩。首页预览使用示例数据；应用登录后显示自己服务器上的实际数据。

用 Android Studio 打开本目录，运行 `app`。也可以在已安装 JDK 17 和 Android SDK 35 的环境运行 `./gradlew assembleDebug`，APK 位于 `app/build/outputs/apk/debug/`。

登录时填写 `IP:端口` 或完整 `https://域名:端口`，再填写 ARTEX 管理员密码。客户端使用 Bearer token；密码不会保存，token 由 Android Keystore 加密后存于应用私有目录。支持从系统相册选择背景图片和调节遮罩浓度。用户自行输入的 HTTP 地址可连接，但公网部署建议使用 HTTPS，以保护登录密码和 token 的传输。
