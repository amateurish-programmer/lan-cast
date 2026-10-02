# 测试和真机验收

## v0.2.1 搜索反馈修复（2026-10-02）

- 修复「重新搜索」在没有接收端时界面文字不变、长时间只显示搜索中而缺少结果提示的问题：点击立即有短提示；12 秒没有发现时显示安装/打开电视接收端及同网提示，搜索仍继续。
- 搜索提示定时任务在停止/重新开始时移除，并检查扫描代次及当前结果；发现电视后不会被旧的空结果提示覆盖。最后一个已发现接收端离线时显示离线提示；系统搜索失败显示错误码。
- 两端版本统一为 0.2.1 / versionCode 3；电视协议、配对确认和固定在线更新地址不变。不能将这项反馈修复称为真机发现故障已解决。
- 最终完整 Gradle 聚合检查通过：四个 module 的 JVM 测试、三个 Android module 的 lintDebug、phone/tv assembleDebug；116 个现有测试零失败/错误。lint 无错误，phone 3、TV 9、updater 1 项警告。没有新增 Android 仪器测试，现有 JVM 测试不覆盖 Toast/Handler/NSD 系统回调。
- 两个最终 APK 已用 aapt 核对包名和版本，用 apksigner 验证签名，SHA-256 证书保持 ecfff34ef90498c034582f4b5fc1f230bdc462a222a43ff9b9776c3f829d6ee3。未进行真实系统覆盖安装。
- 待真机验证：无接收端连续点击刷新、12 秒提示、提示出现后再启动电视接收端、发现期间离线/返回前台、系统搜索失败。无设备环境不能验证 NSD/OEM 运行时或点击实际表现。

## v0.2 云端验证（2026-10-02）

- 最终完整 Gradle 聚合检查通过：shared:test、phone/testDebugUnitTest、tv/testDebugUnitTest、updater/testDebugUnitTest、三个 Android module 的 lintDebug、phone/tv assembleDebug。
- 116 个 JUnit 测试通过：shared 84、phone 14、TV 2、updater 16，零 failures/errors。包含请求批准/取消/过期/断开、来源 IP 与 secret、名称控制字符、发现协议限制、更新 URL/摘要/签名/版本以及 manifest 解析边界。
- 两个 APK 都是 versionName 0.2.0 / versionCode 2，包名保持 dev.lancast.phone 和 dev.lancast.tv。
- 使用 apksigner 验证两个 APK 签名；证书 SHA-256 与已有 v0.1 一致：ecfff34ef90498c034582f4b5fc1f230bdc462a222a43ff9b9776c3f829d6ee3。证书一致不等于实际系统升级测试已完成。
- lint 无错误，phone 2、TV 9、updater 1 项非阻断警告（旧备份声明、文字国际化、TV资源/API建议和持久更新状态同步写入）。
- 独立静态安全检查修复了发现队列无界、设备名控制字符、取消过程中遗失请求secret和旧响应覆盖状态的问题。
- 已审查 dp/sp、滚动布局、系统 inset、至少50dp手机操作控件、设备卡片可访问性、TV焦点样式与拒绝默认焦点。
- 没有 emulator、Android system image、adb、/dev/kvm 或连接的真实设备。没有真实界面截图；运行时排版、NSD广播、TV遥控器、视频播放与系统安装/权限返回流程均未进行设备验收。

## v0.1 历史云端验证（2026-10-02；不代表 v0.2 通过）

- shared Range/LAN URL/限速/会话：40 个 JUnit 测试通过（直接 Kotlin 编译）。
- MPEG-TS 封装/PTS/PCR/CRC/ADTS/AVC：11 个 JUnit 测试通过。
- MPEG-TS 集成：2 秒合成 H.264 + AAC，60 视频帧/95 音频帧；ffprobe 识别 H.264 320×180、AAC 48kHz stereo，两轨起点 0.100 秒；FFmpeg 解码没有报告错误。
- TV LAN 配对/地址校验：5 个 JUnit 测试通过。
- TV Kotlin Android 源码已用 Android35 + Media3 依赖直接编译。
- 最终 Gradle 8.9 / Kotlin 2.0.21：`shared:test` 51 个、`phone:testDebugUnitTest` 10 个、`tv:testDebugUnitTest` 5 个，共 66 个测试全部通过。
- `phone:assembleDebug`、`tv:assembleDebug` 均成功生成 APK；`phone:lintDebug`、`tv:lintDebug` 均成功（手机 1 项、TV 9 项非阻断警告，主要为旧版备份声明、TV 布局和国际化提示）。
- 额外服务停止/旧请求作废/Activity 重建/响应体限额的代码审查修复已合入。单元测试不等于 Android UI/硬件生命周期真机验收。

## 尚未执行

没有连接 vivo S20 Pro 或 Sony KD-65X9088H。以下全部须在实机验证；不预填成功：

### 安装/生命周期

- 两端首次安装、启动、TV 遥控器方向键/确认/返回、页面可见布局。
- 不给通知权限仍有系统前台服务提示；拒绝/取消文件、音频、屏幕共享授权不会开始投屏。
- Android14/15 每次重启镜像重新系统授权；连续点击、停止再开始、返回主页、系统终止共享、旋转屏幕不崩溃。
- vivo 后台限制、锁屏/息屏、切换 Wi-Fi、拔电视网线、系统回收进程、端口占用后的错误提示和可重试性。

### 本地视频

- 自有 MP4 H264/AAC 1080p：播放 30 分钟，暂停/继续、遥控器快进/后退、接近结束 seek。
- 0字节、损坏文件、HEVC/大文件、云端非 seekable 文档；不支持时清楚报错。
- 连续选择新文件、播放中停止、旧 URL 重新请求失败，配对过期后不能继续发命令。

### 影音镜像

1. 先播放自有无 DRM 测试片：画面、声道、口型同步、平均帧率、端到端延迟、30分钟发热/掉帧。
2. 横竖屏/单应用共享/全屏共享；显示比例和固定编码画布限制。
3. 系统停止共享后 TV 结束；慢接收者断开不会持续堆积内存。
4. 分别测试红果、爱奇艺、腾讯视频，记录 app版本、片源、账号、画面可见/黑屏、音频可闻/静音。不同平台不得互推结论。
5. 若目标应用禁止采集，明确记录不兼容并使用其官方投屏；禁止尝试绕过保护。

### 安全

- 自动发现本项目接收端；多电视重名、反复刷新、服务丢失、Wi-Fi 隔离、VPN、多网卡、IPv6-only 网络错误提示。
- 连接请求：电视明确批准/拒绝、60秒超时、手机取消与电视批准并发、返回前台、TV退出、断开与重新连接。未经批准不得取得令牌。
- 错误 secret/IP、请求限速、2小时会话过期、电视断开手机。
- 非配对 IP、错误/旧 token、URL DNS/重定向/外网IP/非法端口/非法路径被拒绝。
- 只在可信 LAN；抓包可见明文的已知限制仍存在，不作加密验证通过声称。

### 更新

- 无更新 manifest、GitHub不可达/限流时错误清楚；有发布时准确显示版本与说明。
- 手机和电视均在应用内显示新版本说明、下载进度、取消；不依赖电视浏览器。
- 缺失/超限/畸形 manifest、不匹配 SHA256、包名错误、签名错误、相同/更低 versionCode、非许可 URL/重定向必须拒绝。
- 下载中切换界面/旋转/退出、反复点击检查、权限页返回、安装取消、缓存篡改后重试必须安全。
- 从系统安装未知应用设置返回后重新验证 APK，再由用户确认打开系统安装器；实际安装升级和拒绝路径须真机测试。
- 后续同签名高 versionCode 覆盖安装；不同签名系统拒绝。
