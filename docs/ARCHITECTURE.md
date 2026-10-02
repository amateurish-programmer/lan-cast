# 架构与协议

## 本地视频

手机使用 ACTION_OPEN_DOCUMENT 获取单个文件只读权限。ContentResolver 打开 seekable ParcelFileDescriptor，HTTP 8766 按 Range 精确提供片段，不整文件加载内存。电视通过 Media3 拉取并硬解，手机不转码，因此效率和画质优于屏幕镜像。格式仍受电视解码器支持限制。

## 实时影音

每次开始由 Activity 请求系统屏幕共享，前台 mediaProjection 服务创建一个 VirtualDisplay，并注册停止回调。Surface 接硬件 AVC 编码器；AudioPlaybackCapture 捕获允许的播放 PCM，AAC-LC 编码。共享模块生成 PAT/PMT、PES、PTS、PCR、TS 包；电视以 progressive video/mp2t 流播放。新客户端从带 SPS/PPS 的 IDR 起播，有界队列限制慢客户端内存增长。

HTTP/TCP 路线简单可维护，但不具有 WebRTC 的拥塞控制、自适应码率或低延迟保证。弱网/电视缓冲可能引入秒级延迟，慢客户端可能断开；不能承诺游戏级投屏。镜像实验版需要真实硬编解码器、音视频时钟与长时间压力验证。

音频权限不保证第三方应用允许被捕获。受保护/通话等内容不支持。选择单个应用共享画面不自动限制 playback capture 到同一应用。

## 控制协议 v1

电视 `:8765`：
- POST `/pair` JSON `{"code":"123456"}` → `token`, `expiresInSeconds`, `protocolVersion`
- 后续端点要求 Authorization: Bearer token，来自原配对手机 IP
- POST `/play` JSON `{"url":"http://PHONE:8766/media/<capability>"}`
- POST `/mirror` JSON `{"url":"http://PHONE:8766/stream/<capability>"}`
- POST `/pause`, `/resume`, `/stop`；实时镜像暂停/继续返回冲突提示
- GET `/status`

手机 `:8766` 数据服务只允许配对电视 IP、当前能力令牌、GET/HEAD。本地视频 Range 支持单个 start-end/open/suffix；不支持多区间。仅允许 RFC1918 IPv4 literal，拒绝 DNS、用户信息、查询、片段、非指定端口或路径；电视数据源禁止重定向，防止将任意网页/内网端点作为媒体代理。

## 安全与后续

- HTTP 明文配对及媒体链路**不抵抗 LAN 监听或中间人**。下一版需认证密钥交换、固定/人工验证指纹和 TLS，不能仅把 PIN 当作加密。
- 令牌仅内存，重启需要重配对；日志不应输出能力链接、配对码或授权 Intent。
- 配对失败限速、请求体大小限制、会话期限、限制 IP 和随机 256-bit 能力，属于原型最小防护。
- SDK/Gradle 凭证、GitHub token、签名密钥不进入 APK 或仓库。
- 后续：NSD/mDNS 发现、系统 route/网络变化处理、完整通知媒体控制、可恢复连接、端到端加密、自动校验下载/系统安装更新流。
