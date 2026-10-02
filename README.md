# 局域网投屏 Lan Cast

Android 手机发送端 + Android TV 接收端。面向 vivo S20 Pro / Sony KD-65X9088H 的**实验性原型**，不依赖 Google Cast/GMS。

## 能力与边界

- **本地视频**：系统文件选择器授权单个文件；手机提供带随机能力令牌的 HTTP Range 视频流，电视 Media3 播放，支持暂停、继续及电视端进度控制。不上传云端。
- **屏幕影音镜像（实验性）**：Android MediaProjection → MediaCodec H.264；AudioPlaybackCapture → AAC；自带 MPEG-TS 封装，通过 LAN HTTP 传给电视。目标 720p 级别 / 30fps，实际分辨率、帧率、延迟及同步需要真机验证。
- **配对**：电视六位临时码、尝试限速、会话过期、控制端 bearer token、数据流随机能力令牌与配对 IP 绑定。
- **在线版本检查**：从本仓库公开 GitHub Releases 查询版本，再由用户打开发布页下载及系统确认安装。目前不是静默更新，也不包含自动下载/安装器。

**红果、爱奇艺、腾讯视频等应用是否允许画面或声音采集，必须逐个版本、片源、账号在真机测试。DRM/FLAG_SECURE/禁止音频采集会导致黑屏或静音。本项目不绕过保护、不提取平台私有播放链接。系统自带录屏成功不等于本应用能采集。**

镜像音频可能包含当前用户下其他正在播放且允许录制的应用，不限于屏幕共享选择器中的目标应用；不会录制麦克风。开始前请停止其他不希望共享的音频。

## 安装和操作

1. 在电视安装 `tv` APK 并打开「局域网投屏 · 电视」。允许用户自行选择的来源安装应用。不要关闭系统安全保护。
2. 在手机安装 `phone` APK。二者接入同一可信家庭 Wi-Fi，关闭访客网络隔离；优先电视网线 + 手机 5 GHz Wi-Fi。
3. 手机填电视显示的 IPv4 和六位配对码，确认手机 Wi-Fi IP（多网卡/VPN 时可能需手动修正），点击配对。
4. 本地播放：选择**本机可寻址的视频文件**；云文档虚拟文件/管道不支持随机读取，会明确失败。
5. 镜像：先横屏到需要的方向，点击开始并完成系统授权，然后切换目标应用。先用无保护自有测试视频验证画面与声音。实时镜像不支持暂停，请停止后重启。
6. 结束点击「停止投屏及共享」或系统前台服务通知中的停止；电视也可以停止并刷新配对码。

数据传输当前为**未加密 HTTP**。随机令牌和 IP 绑定减少误操作，不提供抗同网段窃听/中间人攻击保护；仅限可信家庭 LAN，不要做路由器公网端口映射。不要称为生产级安全协议。关闭投屏后不会后台自动重连。

## 开发构建

需求：JDK 17（推荐）、Android SDK 35、Build Tools 35、Gradle 8.9；Android Studio 可安装相应工具。

```sh
./gradlew :shared:test :tv:testDebugUnitTest :phone:assembleDebug :tv:assembleDebug :phone:lintDebug :tv:lintDebug
```

APK：`phone/build/outputs/apk/debug/phone-debug.apk`、`tv/build/outputs/apk/debug/tv-debug.apk`。
SDK 路径设置在未提交的 `local.properties` 或 `ANDROID_HOME`。手机/电视最低 Android 8，影音镜像要求手机 Android 10+；Android 14+ 每次镜像重新请求授权，不复用授权令牌。

目录：
- `phone/`：手机 UI、文件服务、屏幕/播放音频采集
- `tv/`：TV UI、配对控制 HTTP 服务、受限 Media3 数据源
- `shared/`：Range、LAN URL、限速/会话逻辑与 MPEG-TS 封装/JVM测试
- `docs/TESTING.md`：真机验收与测试记录
- `docs/ARCHITECTURE.md`：协议、生命周期与安全边界
- `docs/RELEASING.md`：发布与更新签名要求

## 状态

详见 [测试记录](docs/TESTING.md)。没有连接上述实机，不声称已通过电视播放、长时间稳定性或第三方应用兼容测试。开发构建不等于正式发布版本；首次发行前必须完成硬件验收与长期签名规划。
