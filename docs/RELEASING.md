# GitHub Actions 构建与发布

自动化使用已经存在的 Bash / Java / Android SDK 构建链，不依赖未验证的 Gradle 构建或缺失的 Wrapper。固定 JDK 17、Android Platform 35 和 Build Tools 35.0.0；GitHub 官方 Actions 固定到完整提交 SHA。

## 两条工作流

- **Android CI**：推送 `main`、Pull Request 或在 Actions 页面手动运行时执行宿主回归、重建 metadata/scrcpy 服务端、构建并校验开发 APK。产物保存 14 天，不创建 Release。
- **Android Release**：仅推送 `v*` tag 时执行。要求 tag 与 `app/build.gradle` 的 `versionName` 精确匹配；通过全部回归、构建和签名校验后上传完整 Release。
- `v0.1.3-dev`、`v1.0.0-rc.1` 等预发布版本自动标记为 Pre-release；`v1.0.0` 为普通 Release。发布标签遵循 `vMAJOR.MINOR.PATCH[-PRERELEASE]`，不支持 `+build-metadata`。
- CI 与构建 job 仅有 `contents: read`；只有独立的发布 job 获得 `contents: write`。PR 不读取发布私钥，也不能发布。
- 同一 tag 的发布串行执行且不取消正在运行的发布。已公开的 Release 不覆盖；中断留下的、指向同一提交的 draft 可通过重新运行失败工作流继续上传，然后一次性公开。

## 首次配置：固定签名身份

在仓库 **Settings → Secrets and variables → Actions** 中，由密钥持有人配置以下三个 Repository secrets：

| 名称 | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 既有 JKS/PKCS12 签名密钥文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_PASSWORD` | 私钥密码；如果与密钥库密码相同，仍单独配置相同值 |

仓库已按所选的既有 PKCS12 密钥固定公开签名信息：alias 为 `key0`，证书 SHA-256 为：

```text
05E874A19ACBF69C1973F3D3888ECCBA45A9A42CACC0AD51EFB0267AE6D7896B
```

这些公开信息配置在 `.github/workflows/android-release.yml`；无需另加 Repository variable。只有刻意迁移签名身份时才修改它们。文件扩展名为 `.jks` 但容器实际为 PKCS12 也受 JDK 17 / apksigner 支持，无需转换私钥格式。

仓库官方配置入口：[Actions secrets 设置](https://github.com/Antman2023/goldadbhelper/settings/secrets/actions)。工作流不会检查或声称这些 Secrets 已在你的仓库中配置，缺少配置时会在运行开始明确失败。

在自己的可信电脑上读取证书信息；下面命令会在终端交互询问密码，不要把密码写进命令历史：

```bash
keytool -list -v -keystore /private/path/release.jks -alias key0
```

本地生成 Base64 文件后，通过 GitHub 的 Secret 输入界面粘贴其内容，配置完删除临时文件。Base64 **不是加密**，须像私钥一样保密：

```bash
# Linux / macOS；在安全的私有目录运行。
umask 077
base64 < /private/path/release.jks | tr -d '\n' > /private/path/keystore.base64
```

不要把私钥、Base64、密码提交到 Git，不要发到 Issue、日志或聊天。保留离线备份。工作流不会生成发布私钥，不会在缺少 Secret 时降级使用开发签名。私钥只在签名步骤解码到 runner 的临时目录，步骤退出时删除；产物上传使用明确的文件清单，不包含该目录。

发布前确认所用密钥就是希望长期使用的身份。只有包名相同、签名兼容且 `versionCode` 满足升级要求，才能覆盖升级已有安装。已交付开发 APK 的证书指纹为 `066C8F52C868F10F1E4339A1946DAE6C829DB4FD2B6939115E2DBDD94C6F5BF3`，与当前选定发布身份不同。仓库原先的 `build/debug.keystore` 与每个新 CI runner 的开发密钥不能视为发布密钥；改用另一把已有密钥通常需要卸载开发包，可能丢失应用数据与 ADB 身份。CI 开发 APK 仅用于临时测试，不保证跨次运行覆盖安装。

建议在仓库设置中限制谁能推送 `v*` tags，并保护发布相关源码/工作流的更改。任何能修改并触发带 Secrets 工作流的人都可能取得签名权限。组织限制 Actions 写权限时，需要管理员允许发布 job 的 `contents: write`；不需要额外 PAT。

## 发一个版本

1. 在 `app/build.gradle` 同时更新 `versionName` 和 `versionCode`。`versionName` 是脚本构建、文件名、元数据与 tag 的唯一版本来源；每次需要覆盖升级的新版本必须递增 `versionCode`。
2. 更新变更说明及相关验证状态，提交到 `main`，等 Android CI 通过。
3. 确认签名配置正确后，手动推送对应 tag。例如源码版本为 `0.1.3-dev` 时：

   ```bash
   git pull --ff-only
   git tag -a v0.1.3-dev -m 'GoldenBeetle 0.1.3-dev'
   git push origin v0.1.3-dev
   ```

   这一步会真的触发 GitHub Release。添加工作流或普通 push 不会发布。不要移动已发布 tag，也不要用同一 tag 覆盖不同二进制。
4. 在 Actions 的 Android Release 运行中确认全部成功，再查看 Releases 页面。

如果失败，先查看具体步骤：版本不匹配就修改版本/tag；缺少签名参数就在 GitHub 安全设置中补齐；证书指纹不匹配先核对密钥，不要为了绕过校验盲目改指纹。修复源码需要新的提交与 tag。只补齐 Secrets 或恢复上传时，可对原运行使用 **Re-run failed jobs**。已公开 Release 重新发布会被拒绝，需发新版本。

## Release 附件

- `goldadbhelper-VERSION.apk`：固定发布密钥签名的 APK
- `goldadbhelper-VERSION-source.zip`：与该 APK 构建输入一致的源码、重建服务端资源、工作流与第三方许可证
- `source-inputs.json`：每个源码/构建输入文件的 SHA-256
- `build-metadata.json`：提交、tag、版本、签名证书、APK/源码哈希及工具链信息
- `signing-certificate.txt`：apksigner 验签结果和公开证书指纹
- `SHA256SUMS.txt`：上述附件的 SHA-256；下载全部附件后执行 `sha256sum --check SHA256SUMS.txt`

源码归档取自回归结束、服务端重建后的实际输入，而不是将 GitHub 自动生成的源码 ZIP 假称为完整构建快照。归档不会包含 `build/` 开发密钥。

分发前还会核对 `构建来源.json` 中已记录的 APK、源码 ZIP 和输入清单哈希，并逐条验证 ZIP 内容与清单一致；替换归档后重新计算哈希不能绕过此检查。正式签名包的有效 ZIP 条目必须与记录的未签名候选一致，仅允许签名相关的 JAR 条目变化；签名块由 apksigner 独立验证。内置两个服务端资源还须与源码清单一致。重复 ZIP 条目、版本不符或任何内容差异都会在创建 `dist/` 前停止打包。

## 本地复现

普通开发构建仍使用原命令：

```bash
export ANDROID_HOME=/path/to/android-sdk
python3 -m unittest discover -s tests/release -v
bash tools/test-all.sh
bash tools/test-followup-navigation-server.sh
bash tools/build-servers.sh
bash tools/build-apk.sh
python3 tools/package-artifacts.py --mode debug
```

发布构建先使用 `APK_SIGNING_MODE=unsigned bash tools/build-apk.sh`，再按 `tools/sign-apk.sh` 要求从安全环境注入 `RELEASE_*` 参数，对 `build/aligned.apk` 签名到 `build/release-signed.apk`，最后执行 `python3 tools/package-artifacts.py --mode release`。不在文档或命令行中填入真实密码。`dist/` 必须为空，避免把旧产物误发为新版本。

工作流覆盖宿主测试、编译、APK 结构、包名/版本、对齐、签名和源码输入检查；不运行模拟器或真实设备。绿色 CI 不代表真实 Android 生命周期、OEM、USB/Fastboot 和 H.264 投屏已经验收，仍以 [待验清单](REMAINING_VALIDATION.md) 为准。
