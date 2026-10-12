# Android 11+ 无线 ADB 配对

## 接口与界面集成

- 使用与 `AdbSession` 相同的 `AdbIdentity`，不要为每次配对另建身份。
- 在工作线程调用 `new PairingClient(identity).pair(host, pairingPort, codeChars, 30000)`。
- `codeChars` 是 6 位数字字符数组；方法退出时会清零它。不要存储或记录配对码。Android 文本框应使用数字密码输入，提交后清空。
- 只有返回 `PairingClient.Result` 才显示“配对完成”。结果中有经过 SPAKE2 认证的 `deviceGuid`。
- `PairingClient.close()` 可从 UI 取消按钮或生命周期清理调用，会关闭底层 socket，使正在等待的读操作结束。总时限也会关闭连接。
- 配对端口与连接端口不同。完成后提示用户返回 Android“无线调试”主界面，输入那里显示的连接端口。
- 连接使用 `new TlsTcpTransport(host, connectPort, timeoutMs)` 与同一身份构造 `AdbSession`。ADB 的 STLS 交换完成后才升级到 TLS 1.3，继续读取加密的 CNXN，不能发送第二个 CNXN。
- 密钥写入仅由应用既有的私有身份存储负责。配对类不写文件、偏好设置或日志。
- 可以只保存主机地址、GUID、配对时间等非秘密历史，忘记历史不能宣称已从目标设备撤销授权；目标端需在无线调试的已配对设备列表执行忘记。

## 实现

1. TLS 1.3 双向证书握手，客户端证书使用本应用自己的 RSA-2048 ADB 身份。
2. Conscrypt 导出 64 字节 TLS keying material。标签是 `adb-label` 加结尾 NUL，context 为 null。
3. 六位码的 ASCII 字节与 exporter 结果拼接，交给 BoringSSL 兼容 SPAKE2（Edwards25519）。客户端/服务器名称分别是 `adb pair client` / `adb pair server` 加结尾 NUL。
4. 版本 1 配对帧头：1 字节版本、1 字节类型、4 字节大端长度。SPAKE 消息为 32 字节。长度、类型、版本先校验后分配。
5. SPAKE2 共享密钥通过 HKDF-SHA256 派生 16 字节 AES 密钥；info 为 `adb pairing_auth aes-128-gcm key`，不包含结尾 NUL。AES-GCM 使用 12 字节 nonce、低 8 字节小端序号、128 位 tag。
6. 本方发类型 0 的 8192 字节 PeerInfo，内容为本身份的 ADB RSA 公钥；对端必须返回类型 1 的设备 GUID。解密认证、长度与 GUID 检查全部完成才返回成功。

## 信任边界

配对阶段由 SPAKE2 和 TLS exporter 一起认证一次性配对码，并绑定当前 TLS 通道。配对服务器证书不由公共 CA 签发。

AOSP adbd 的连接服务器另行生成自己的随机证书密钥，不能拿配对服务器证书作为后续连接的 pin。`Result.pairingCertificateSha256` 只用于诊断，不是连接信任记录。本实现的 STLS 信任方式遵循 AOSP host：TLS 加密会话、设备验证客户端已配对的公钥；客户端不获得可跨重启校验的长期服务器身份。不要将这里的 trust manager 用于 HTTPS 或其他协议。仅连接用户自己确认的受信局域网地址。

此实现和上游纯 Java SPAKE2 都没有独立安全审计。Java 运行时不能保证对所有中间对象、密码框字符串或 JCA 内部密钥副本做可靠清零；本实现保证不主动记录、上传、持久保存配对码，并清零可控的字符/字节数组。

## 依赖与许可证

- 运行时：官方 Maven Central `org.conscrypt:conscrypt-android:2.5.3`，Apache-2.0。必须打包该 AAR 的 classes 和正确 ABI 的 JNI 库；无需 Android 隐藏 API 绕过。
- JVM 测试：官方 Maven Central `org.conscrypt:conscrypt-openjdk-uber:2.5.2`，仅用于本机测试。
- SPAKE2：`MuntashirAkon/spake2-java`，固定提交 `7615ddd680b990e14513ebb66eac4cb0dbf82464`，LGPL-3.0。12 个源码文件及原始 Git blob 哈希见 `third_party/spake2-java/UPSTREAM_MANIFEST.json`。
- 修复用的 field multiply：`str4d/ed25519-java`，固定提交 `e0ac35769db8553fb714b09f0d3f3d2b001fd033`，CC0，来源 `src/net/i2p/crypto/eddsa/math/ed25519/Ed25519FieldElement.java`。
- 协议参考：`MuntashirAkon/libadb-android` 固定提交 `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`（Apache-2.0 选项），及 AOSP `platform_system_core` 的 `android-11.0.0_r1` 下 `adb/pairing_auth`、`adb/pairing_connection`、`adb/tls/tls_connection.cpp`、`adb/daemon/auth.cpp`。
- 没有使用被分析 APK 的代码、二进制、图片、签名密钥、后台或配对凭据。

## 上游修复与重新构建

保留完整第三方源码、许可证、版权标记和本应用可重新构建的源码；分发 APK 时同时分发对应源码包与构建说明，满足 LGPL 的修改/重新链接要求。接收者可替换 `core/src/main/java/io/github/muntashirakon/crypto/` 中兼容源码后重新构建 APK，并使用自己的签名安装；没有额外反修改限制。修改版 APK 的签名不同，覆盖已安装版可能需要先卸载（会丢失其应用私有身份）。

在上游纯 Java 实现上进行了这些有记录的修改：

1. 删除输出临时私钥、DH 共享值及中间曲线点的诊断打印。
2. 清零临时随机私钥数组。
3. 点解码的 sign bit 修复：Java 的 byte 是有符号类型，使用 `(s[31] & 0xff) >>> 7`。
4. 恢复其原始 CC0 EdDSA-Java signed-radix field multiply。上游替换后的 unsigned 版本与其其余有符号 field 表示不兼容，连 `1 * B` 都无法保持基点；不能直接以该版本用于配对。

## 验证

运行 `bash tools/test-pairing.sh`。使用 Conscrypt JVM jar 时，设置 `CONSCRYPT_TEST_JAR=/absolute/path/conscrypt-openjdk-uber-2.5.2.jar`。

2026-10-12 验证记录：14 项测试全部通过，包含实际 TLS 1.3/exporter 的 loopback 测试；Android API 35 bootclasspath 的 core 编译通过。Conscrypt OpenJDK 2.5.2 在较新 JDK 下需要测试脚本中仅对本次 JVM 进程生效的 `--add-opens=java.base/java.net=ALL-UNNAMED`，不改变系统配置，也不用于 Android 运行时。

- 独立 Python 仿射 Edwards25519 算术生成 4 组固定合成向量，与 Java 的客户端消息、服务器消息和共享密钥逐项比对。生成器只用于测试，非恒定时间，绝不用于产品密码学。
- 随机双方 SPAKE2 / AES-GCM、错误密码、篡改 tag、异常帧长度、无效版本、GUID 类型/长度、销毁状态、同一身份的 X.509 证书和配对码清零测试。
- 安装官方 JVM Conscrypt 后运行真正 TLS 1.3/exporter + 双向证书 + 完整配对帧的 loopback 服务，含错误码、超时、取消，以及 ADB STLS 到加密 CNXN。
- 以上合成测试不等于 Android 实机兼容性证明。未连接或操作任何真实设备；不同 Android 版本/OEM、Wi-Fi 网络变化、设备端取消和授权保存仍需用户授权后的实机验收。
