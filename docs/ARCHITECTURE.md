# 架构与协议 · v0.2

## 本地视频与实时影音

手机使用 ACTION_OPEN_DOCUMENT 获得单个视频的只读权限，以 ContentResolver 打开 seekable ParcelFileDescriptor。HTTP 8766 精确提供 Range，不将文件整体读入内存或上传云端。电视 Media3 拉取并硬解，格式支持取决于电视解码器。

镜像由系统授权 MediaProjection → VirtualDisplay → 硬件 AVC 编码器；AudioPlaybackCapture 只捕获允许录制的播放 PCM，再编码 AAC-LC。共享模块生成 MPEG-TS PAT/PMT、PES、PTS、PCR；电视 progressive video/mp2t 播放。新客户端从带 SPS/PPS 的 IDR 起播，有界队列限制慢客户端内存。

HTTP/TCP 没有 WebRTC 拥塞控制、自适应码率或低延迟保证。弱网/电视缓冲可能产生秒级延迟，不能承诺游戏级镜像。音频可能包含其他允许捕获的应用；不会录制麦克风。不绕过 DRM、FLAG_SECURE 或禁止音频采集。

## 自动发现与控制协议 v2

- TV 接收界面运行时以 Android NSD 注册 `_lancast._tcp.`，端口 8765，TXT `protocol=2`。服务名仅用于展示，不能作为身份验证。
- 手机仅发现本项目协议的接收端，不支持任意第三方电视或 Google Cast/DLNA 接收器。
- 手机选择设备后向 `/pair/request` POST `{"deviceName":"..."}`。电视仅允许一个待确认请求，60 秒过期，返回随机 requestId/requestSecret；所有连接都必须经电视用户显式批准。
- `/pair/poll` POST `{"requestId":"...","requestSecret":"..."}` 返回 pending/approved/declined/expired/cancelled。批准后返回 token；secret 和手机来源 IPv4 共同限制读取。请求名称是不可信展示内容。
- `/pair/cancel` 取消该请求并撤销该请求已批准的会话，避免手机取消和电视确认并发留下连接。
- 控制端点 `/play`、`/mirror`、`/pause`、`/resume`、`/stop` 和 GET `/status` 要求 Bearer token 与原手机 IP。实时镜像不支持暂停。
- 会话两小时过期，状态只在内存中。停止接收、拒绝、超时或断开不自动重新批准。
- 手机根据到已选电视的实际路由获取自己的私有 IPv4，无需用户填写 IP。

请求体/响应体有大小上限；待确认请求数量和新请求速率受限。手机失去请求所有权或取消时，旧网络响应不得覆盖新状态；电视主线程消费排队命令前再次验证会话。

## 数据服务边界

手机 `:8766` 只允许已配对电视 IP、当前随机能力令牌和 GET/HEAD。本地视频支持单个 start-end/open/suffix Range。仅接受 RFC1918 IPv4 literal，拒绝 DNS、用户信息、查询、片段、非指定端口/路径；电视数据源禁止重定向，避免变成任意网页或内网端点的媒体代理。

## 更新

两端共用 `updater` 模块。公开 manifest 指向固定 Git commit 下的 APK；应用内展示说明、下载并检查大小/摘要、包名、递增版本号和已安装签名，再交给 Android 系统确认安装。manifest 与散列不是独立信任根，已安装应用签名是最后的升级信任锚。安装来源权限由用户在系统设置授予，应用不自行开启，不静默安装。详见 RELEASING.md。

## 已知安全限制

控制、配对和媒体仍是 HTTP 明文，**不抵抗同一 LAN 监听或中间人攻击**。TV 显式批准不是加密认证。只用于可信家庭局域网，不应做公网端口映射。后续生产版本仍需要认证密钥交换和加密链路。

GitHub 凭据、签名私钥和 SDK/Gradle 凭据不进入 APK、manifest 或仓库。debug 签名仅适合测试，长期安全发布需要维护者管理签名。
