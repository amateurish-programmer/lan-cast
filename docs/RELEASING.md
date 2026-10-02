# 发布与应用内更新 · v0.2

公开源码：https://github.com/amateurish-programmer/lan-cast

### v0.2.1 / versionCode 3

手机「重新搜索」增加即时反馈；搜索 12 秒未发现时显示电视接收端安装、开启及同网提示，并继续搜索。接收端离线与系统搜索失败有明确提示。电视仅同步发行版本号；配对协议和更新渠道不变。此版未完成真机发现、播放或系统升级验收，不承诺解决路由器隔离、未安装接收端或厂商 NSD 问题。

## 更新来源

手机和电视共用更新模块，不需要浏览器或 GitHub 登录。读取固定公开地址：

`https://raw.githubusercontent.com/amateurish-programmer/lan-cast/updates/manifest.json`

manifest 描述版本、变更说明与两个 APK。APK URL 必须指向本仓库固定 40 位 Git commit 下的 `apks/v<版本>/<phone|tv>.apk`，不能是任意网站或可执行指令。`updates` 分支与源码分开保存发行文件；源码 main 不提交签名私钥或 APK。

```json
{
  "schemaVersion": 1,
  "versionName": "0.2.0",
  "versionCode": 2,
  "notes": "变更说明",
  "artifacts": {
    "phone": {
      "packageName": "dev.lancast.phone",
      "url": "https://raw.githubusercontent.com/amateurish-programmer/lan-cast/<实际40位commit>/apks/v0.2.0/phone.apk",
      "sha256": "<实际64位SHA256>",
      "size": 123
    },
    "tv": {
      "packageName": "dev.lancast.tv",
      "url": "https://raw.githubusercontent.com/amateurish-programmer/lan-cast/<实际40位commit>/apks/v0.2.0/tv.apk",
      "sha256": "<实际64位SHA256>",
      "size": 456
    }
  }
}
```

示例不是可发布 manifest；所有占位符必须由真实构建和上传 commit 生成。先提交 APK，再用该提交的 SHA 提交 manifest，避免循环引用。

## 校验与安装

应用检查 metadata 结构/大小和版本，显示说明，用户选择下载后显示进度。完整下载后检查大小、SHA-256、APK 包名、递增 versionCode 和已安装应用签名，再请求系统安装器。未知来源安装权限由用户在系统设置授予；返回应用后重新验证，再由用户决定继续。下载取消或退出不能留下可自动安装的半成品。不静默安装、不自动接受 Android 权限提示。

HTTPS 和 SHA-256 可发现传输/发布错误，但 manifest 与 APK 来自同一渠道并不形成独立信任。已安装应用的签名证书是覆盖升级的信任锚。签名不匹配、降级、同版本、错误包名或被修改文件必须拒绝。

## 测试签名与长期发布

当前 APK 是测试用 debug 签名。v0.2 本地构建复用了先前 v0.1 的现有密钥；应比较两版 `apksigner verify --print-certs` 的 SHA-256 确认。不同机器 / GitHub Actions 临时 debug key 不保证相同，不能把任意 CI artifact 当成兼容更新。

debug key 不适合作为生产安全根。长期发行需要维护者决定并妥善保管正式签名；不得将 keystore、密码或 GitHub 凭据提交源码、更新分支或 manifest。更换签名通常无法直接覆盖已安装测试版，需规划迁移并告知用户。脚本不会创建或上传签名密钥。

## 发布步骤

1. 提高两个 app 的 versionCode 和 versionName，运行完整 tests/lint/build，并完成必要真机矩阵。
2. 使用原有授权签名构建；确认包名、versionCode、versionName、签名摘要和 APK 大小。
3. 将 APK 提交到 `updates` 分支的 `apks/v<version>/phone.apk` 和 `tv.apk`，记录真实提交 SHA。
4. 使用 `tools/update_manifest.py`，输入实际提交、APK、变更说明和预期原有签名摘要，生成 manifest。脚本先运行 apksigner/aapt 并计算 SHA-256。
5. 提交 manifest，再从公开固定 URL 下载读取 manifest 与 commit URL 两个 APK，验证字节数和 SHA-256，不能只凭上传成功声称可用。
6. 真机从上一版原位升级，检查下载取消、安装取消、未知来源权限返回、错误签名与断网路径。Android 安装器的最终确认始终由用户操作。

CI 只做源码测试/lint/debug 构建，不生成持久签名或自行发布。没有发布可用 manifest、网络不可达或元数据无效时，应用明确报告失败。
