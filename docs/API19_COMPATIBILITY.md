# Android 4.4 / API 19 compatibility audit

## Build contract

The host manifest and Gradle `minSdk` are now 19, with target/compile SDK 35. This is a compatibility implementation, not a claim that all hardware/OEM behavior has been validated.

The manual build performs both D8 language desugaring and L8 core-library desugaring, pinned to Google's `desugar_jdk_libs_nio:2.1.5`. The latter supplies `java.util.Base64`, function interfaces, comparator/default collection methods, String joining and NIO references. D8 also backports `Float.isFinite`. Merely lowering `--min-api` would not supply these implementations.

AndroidX multidex 2.0.1 is installed in `BridgeRuntime.attachBaseContext`. Main-dex rules retain the application and installer. The app and L8 dex files stay separate as D8 requires. The candidate emitted three DEX035 files and a valid v1/JAR signature, both necessary on Dalvik/API19. Build inputs, upstream artifact URLs and hashes are in `third_party/desugar/README.md`; licenses are packaged offline in the app.

`BUILD_DIR=build/compat/apk bash tools/build-apk.sh` isolates build output and uses the same development signing key as other project builds. The Gradle path declares matching desugaring/multidex settings but has not been independently executed here.

## Host API inventory and fixes

- `Compatibility.Api21` isolates elevation, clipping outline, ripples, status-bar color, checkbox tint, Switch show-text, USB product name, APK split paths, no-backup directories and modern MediaCodec input buffers. API19 uses existing old buffer arrays, state-list pressed feedback and app-private credential storage. No backup is permitted by the manifest on every supported API.
- Resource qualifiers select Holo on API19 and Material on API21+. Light status/navigation attributes are split at API23/26. API19 gets a raster rendering of the independently authored launcher vector; API21+ keeps the vector.
- Window inset listeners are isolated; API19 uses `fitsSystemWindows`. All existing Main/UI calls are routed through the compatibility boundary.
- SAF single-document operations exist on API19. Document-tree APIs start at API21; file-directory picker paths are the API19 route. ScreenshotStore guards tree APIs in an isolated nested class and supports a local `file:` directory with exclusive creation. The local file browser handles storage permission flows for each OS version.
- Register-receiver flags are only used behind an API33 guard. Runtime storage permission methods are only called on API23+; all-files settings are isolated for API30+.
- Core `StandardCharsets`, suppressed exceptions and try-with-resources are available at API19. Newer Java APIs are backported rather than silently omitted.

Reproduce the compiled-member inventory with:

```
python3 tools/audit-min-api.py --classes build/compat/apk/classes > build/compat/api-usage.txt
```

This reads the official installed SDK `api-versions.xml`, including inherited members. It lists references requiring a guard or backport; it is an inventory rather than a control-flow proof. Review new entries before lowering or claiming support for any further API level.

## File-transfer safety is preserved

Old desugared NIO implementations can approximate filesystem operations and omit inode identity. They are deliberately NOT trusted for Android local-source verification. `AndroidFileAccess` invokes real `open(O_NOFOLLOW|O_NONBLOCK)`, `fstat`, `lstat`, `O_CREAT|O_EXCL`, read/write and fsync primitives:

- API21+: the public `android.system.Os` API
- API19/20: the equivalent pre-move `libcore.io.Os` interface, with constants and struct fields resolved before use
- KitKat does not publish O_CLOEXEC/O_DIRECTORY: descriptors are immediately marked FD_CLOEXEC through fcntlLong(F_SETFD), and directory opens are checked by fstat before use. API21+ retains atomic flags and public fcntlInt. The app does not fork/exec local child processes between open and fcntl.
- Missing required OEM interfaces or constants: fail closed, with an IOException; never fall back to test-then-write or overwrite

References for the old interface and struct are official AOSP:
- https://android.googlesource.com/platform/libcore/+/android-4.4.4_r2/luni/src/main/java/libcore/io/Os.java
- https://android.googlesource.com/platform/libcore/+/android-4.4.4_r2/luni/src/main/java/libcore/io/StructStat.java

The local upload hash compares descriptor/path inode, size, metadata and SHA256 before and after transfer. Local output creation uses O_EXCL; failed final copies are retained for inspection because checking an inode and then unlinking is racy. The remote helper's existing exclusion, replace-race rejection and UNKNOWN outcome logic are unchanged; a legacy filesystem adapter exposes the same primitives. API19-26 timestamp fields have second resolution; content hash checks remain required.

The metadata and file-commit helper is now DEX035/min19. Metadata minSdkVersion is guarded at API24 and long version codes at API28. Missing minimum-SDK information is explicitly marked unavailable. Partial per-package errors include a fallback label and metadataUnavailable marker.

Shell-v1 exit status is explicitly framed for one-shot commands using a unique trailing acknowledgement and validated 0..255 code. Missing/truncated replies are errors. Interactive raw streams remain separate. mkdir/readlink use validated absolute quoted paths without unsupported `--`. Other legacy toolbox CRUD gaps are tracked explicitly in feature-status.json rather than claiming universal flag compatibility.

Local file-picker results bind a stable file identity token. Selected sources are copied to a new app-private staging file with O_NOFOLLOW, regular-file checks, identity comparisons and two source SHA256 passes, then the persisted snapshot is hashed again. Final review/confirmation binds to those private bytes. Directory tokens use dev/inode/type rather than timestamps; live ParcelFileDescriptor duplicates anchor output via /proc/self/fd, so replacing the selected pathname cannot redirect subsequent file creation. Nested names reject separators/traversal and files use O_EXCL.

ART byproducts are cleaned before helper deletion using a strict workspace/name whitelist. The helper only walks oat/{arm,arm64,x86,x86_64}/metadata-server.{odex,vdex,art}, holding no-follow directory descriptors at each level. Unknown files cause a retained-workspace warning; no recursive delete is used.

## Screen behavior is a separate remote compatibility boundary

Host API19 can decode H264 with MediaCodec's legacy buffers. The bundled public scrcpy2.4 remote server itself requires API21. An API19 *remote* therefore needs screenshot compatibility mode, and old adbd cannot be assumed to support `exec:` binary streams. The separate screenshot fallback is being implemented/tested using an exclusively owned temporary PNG plus binary-safe SYNC download; no blind CRLF conversion is safe for PNG bytes.

## Verified and pending

On this implementation, the following host-JVM regressions passed: CoreTests (12 cases), DomainTests (201 assertions), ProtocolTests (220), ScreenProtocolTests (53), UiSafetyTests (10), FileTransferTests (19), ActivationTests (278), InteractiveShellTests (716), and FileCommitServerTests (17 cases). Fixtures are synthetic and do not substitute for device tests.

The min19 candidate passed AAPT metadata and APK signature verification and includes the backport dex. Actual API19 installation, app navigation, multidex startup, private identity generation, TLS/provider behavior, media paths and kernel-backed transfer fixtures remain subject to the independent API19 emulator QA. USB/OTG and Fastboot still need physical hardware. Do not mark those checks passed based on this document alone.

## Device evidence update

The first min19 APK installed and launched on the official API19/x86 emulator; classes2/classes3 loaded via AndroidX and the initial consent UI plus settings/license/Fastboot entry showed without an app theme/vector/verifier crash. The later isolated kernel probe confirmed exact binary round trip, fstat/lstat identity, O_EXCL refusal/no overwrite, actual symlink refusal, RSA/Base64, collection defaults, String.join and Float.isFinite. A separate local-source/directory probe passed 15 checks, including chosen-source replacement rejection and descriptor anchoring after the chosen directory path was replaced. These probe patches are not evidence that an older frozen APK already contains the latest fixes; final APK QA tracks its own hash. TLS/provider and complete remote-helper behavior remain under device investigation at this point.

The API19 TLS loopback probe separated provider loading from exporter behavior. Its first JNI failure was a fixture setup issue and is not claimed as an app defect. With the correct native library search path, the production provider/context and TLS1.3 handshake work. A real API19-21 compatibility issue then appeared: Conscrypt 2.5.3 wraps client sockets in its KitKat adapter while its public exporter accepts only its internal socket. The fix reads only that exact pinned library adapter's immutable delegate and passes the same negotiated channel to the public exporter. Trust checks, TLS1.3 requirements and handshake are unchanged; no generic socket unwrapping or Android hidden-API exemption is used. Official source/field boundary is recorded in `third_party/conscrypt/PROVENANCE.txt`.

The official API19/x86 Dalvik JIT also aborted with `exceeds size of memVRTable` when compiling the current large SPAKE2 field arithmetic. A focused arithmetic worker is reducing register pressure without changing the algorithm. Interpretation-only probe results must be labeled separately and must not be used to claim default-JIT app compatibility.

The local filesystem fixtures described above were this worker's targeted regression checks, not a completed independent security review. Further adversarial file replacement/redirection fixtures were paused on the parent instruction; no such checks are being retried through another environment.

The exporter patch subsequently passed the actual API19 default-JIT TLS test: mutual-certificate TLS1.3 loopback handshake, application bytes and identical 64-byte exporters on both sides. The result came from a frozen candidate plus a specifically identified TlsSupport probe override; final APK integration must be verified separately. `tools/test-api19-tls-device.sh` now defaults to a test-entry-only JAR and records both APK/probe SHA256, avoiding accidental production-class overrides during final APK validation.

## Frozen candidate05 update (2026-10-12)

The unmodified candidate05 APK (`61e2e94e0e2c774ce9c83a7c533f2233a5b64589d6cb76f31df8d5e7cd2b5f97`) passed default-JIT TLS1.3 mutual-certificate loopback, matching exporter, SPAKE2/HKDF/AES-GCM using only its packaged production classes. The arithmetic register reduction and explicit Conscrypt AES-GCM selection are integrated, superseding the earlier failures above. This is not end-to-end Android11 wireless adbd pairing.

Candidate04/05 actual production flows passed 131101-byte binary upload/download with SHA256, refusal of an existing ordinary file, precommit cancellation, metadata label/icon inventory, legacy PNG+SYNC fallback and persistent PTY. Candidate05 UI passed echo/clear-keep-last-line/exit/new-shell/tab-switch. Legacy df reporting and framework-measured available RAM now render; a newly found unlabelled ps state column is fixed in candidate06 source. Exact build and test scopes are in the sibling API19_QA.md and docs/runtime-evidence.json. Both API19 and API26 test VMs were shut down and owned fixture files/forwards cleaned before the API24 ARM screen test.

Candidate06 (`948e2cc6f39082520f6e10bc2b2b6092b4e5b091406b0d2a9dcd79910673968e`) is built and awaiting screen/device regression. Its post-v5 changes are not promoted by earlier pass results. The second independent local-filesystem security review remains incomplete.
