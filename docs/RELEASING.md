# 发布与在线更新

目标公开仓库：https://github.com/amateurish-programmer/lan-cast

应用内「检查在线更新」调用 GitHub 最新公开 Release API，显示 tag 并打开该仓库发布页。用户自行下载对应 phone/tv APK，并由 Android 系统确认安装。GitHub 不可达/限流/未发布时明确失败；APK 无 GitHub token。此初版没有自动更新下载、静默安装或私有发布授权机制。

## 正式发布前

1. 完成 `docs/TESTING.md` 实机矩阵。
2. 决定并由维护者保管长期 release 签名。不要将 keystore 或密码提交仓库，不要在构建脚本内硬编码。
3. 两个 module 同步提高 versionCode/versionName，生成 release APK，并用同一长期签名分别签署各 applicationId 的后续版本。
4. 验证 `apksigner verify --verbose --print-certs`，计算 SHA-256，将 phone/tv APK、SHA256SUMS、变更说明、已知限制放入同一 Release。
5. 验证从上一版原位升级：包名和签名保持一致、versionCode 增加。系统将拒绝不同签名覆盖安装。

Debug APK 仅用于测试。不同机器/CI 临时 debug key 可能不同，不能保证跨构建覆盖安装；不要把临时 debug key 当长期发布签名。release 构建默认未签名，不能直接安装。流水线只构建/检查，不自动正式发布，不创建或注入密钥。

后续应用内下载升级应额外校验目标包名、versionCode、签名证书和 HTTPS/散列，交给系统 PackageInstaller 最终确认；从同一不可信位置下载 APK 与散列不构成独立信任。
