# Package-command compatibility and confirmation scope

Updated 2026-10-12. These are source-verified command policies, with host tests. They are not a claim of completed device acceptance.

## Target API policy

`PackageCommandStrategy` reads the connected target's `ro.build.version.sdk`. The host Android version does not choose remote commands. Missing, mixed or invalid SDK output blocks the operation. The resulting immutable plan is selected before confirmation; command failures never retry with wider user scope.

- API 19/20: single APK uses `pm install [-r] PATH`. Uninstall uses `pm uninstall [-k] PACKAGE`. Both are explicitly confirmed as affecting **all users**, because AOSP uses `INSTALL_ALL_USERS` / `DELETE_ALL_USERS`. They are not user-0-only operations. A nonzero requested user is rejected instead of expanded to all users.
- API 21+: single and split installs and uninstall retain explicit `--user 0` (or an explicitly requested user in core plans). Updates to an already-shared APK can affect the application code used by other installed users, as disclosed in the install dialog.
- API <21: a multi-file split set is rejected before any local APK snapshot or remote transfer. A single file is additionally inspected locally for the manifest's unnamespaced `split` attribute. A detected split is rejected before upload. A missing, oversized (>4 MiB), structurally malformed, unsupported or undecodable binary manifest stops the legacy flow with an undetermined-format error; it is never assumed to be a base APK.
- API <26: the restore action explicitly reports that this app has no verified `install-existing` shell path for that version. It does not try guessed commands, reinstall an APK, or substitute `enable` for restoration. The existing separate enable action remains available for disabled apps.
- API 26+: restore uses `cmd package install-existing --user 0 PACKAGE`. Candidates are limited to system packages reported by `-s -u`, minus currently installed system packages; no UID listing flag is needed.

SDK-based policy expresses the verified AOSP command contract. An OEM may reject a command, and its error must remain visible. There is no post-failure global fallback and no claim that every OEM implements the command.

## Immutable approval and staging

Package dialogs bind the same `AdbSession` object, view generation, target SDK, user scope and command/selected APK count. The file picker and local-app picker capture the original session and generation. Positive confirmation rechecks that binding. Prepared install commands use the saved plan instead of reselecting a strategy after approval. An old dialog cannot acquire permission to operate on a later selected device or page.

APK staging uses mode `0644` so the package manager's separate container service can read it, with random `/data/local/tmp` names and existing cleanup/reporting. KitKat's `pm` passes a file URI to package management; its DefaultContainerService directly opens that path as another application. This compatibility change is confined to staged installation APKs.

`ApkManifestInspector` is a bounded reader of only the compiled XML string pool and first manifest element. It supports the standard UTF-8/UTF-16 pools and performs no reflection, hidden API calls or archive extraction. It does not validate an APK's signature, package integrity, SDK compatibility, remaining manifest or executable content. The platform installer still owns those checks. Text XML or unfamiliar encodings are explicitly unsupported in the legacy inspection path.

## Primary source evidence

All sources below were read from official Android Gitiles on 2026-10-12; when web rendering failed, Gitiles' `?format=TEXT` endpoint was decoded locally.

- [KitKat Pm.java, android-4.4.4_r2](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-4.4.4_r2/cmds/pm/src/com/android/commands/pm/Pm.java): `runInstall()` starts with `INSTALL_ALL_USERS`; `runUninstall()` starts with `DELETE_ALL_USERS`; neither parses `--user`. No session install or install-existing command. Decoded SHA256: `08d4b082ecf2ce305cc4a2898a356f62a44353b6478bb5b9add7aa6621a00e60`.
- [Wear/KitKat Pm.java, android-4.4w_r1](https://android.googlesource.com/platform/frameworks/base/+/android-4.4w_r1/cmds/pm/src/com/android/commands/pm/Pm.java): same relevant implementation and decoded SHA256 as the KitKat source above.
- [Lollipop Pm.java, android-5.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-5.0.0_r1/cmds/pm/src/com/android/commands/pm/Pm.java): install/uninstall parse `--user`; session create/write/commit/abandon are present. Its install usage text omits some supported options, so absence of an option in help is not used to widen scope. Decoded SHA256: `81258e61c2042321b20d79d5b190f4ed27989dc33c86cd6fb450f6430586e7c4`.
- [Nougat PackageManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) lacks install-existing; [Oreo version](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) implements it and parses `--user`. The app intentionally uses the verified API26 floor for this shell path.
- [Current PackageManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java): modern install/session, per-user uninstall and install-existing handling remain present. Decoded SHA256 of the inspected revision: `f51101a2d5c4cd9df4283d9da73707b4d0edc189b75a391285a816931ad3e004`.
- [KitKat DefaultContainerService.java](https://android.googlesource.com/platform/frameworks/base/+/android-4.4.4_r2/packages/DefaultContainerService/src/com/android/defcontainer/DefaultContainerService.java), `copyFile()`, and [its manifest](https://android.googlesource.com/platform/frameworks/base/+/android-4.4.4_r2/packages/DefaultContainerService/AndroidManifest.xml): file URI reading by a separate service/application supports readable APK staging.
- [Lollipop PackageParser.java](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/pm/PackageParser.java), `parsePackageSplitNames()`: reads the root unnamespaced `split` string; an empty value is treated as a base APK.
- [AOSP ResourceTypes.h](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/include/androidfw/ResourceTypes.h): official string-pool, compiled XML node, start-element and attribute layouts used by the narrow legacy split inspection.

## Verification and remaining work

Host-only tests and compile logs: `build/package-compat-check/`.

- `PackageCommandStrategyTests`: 133 assertions over APIs 19/20/21/23/24/25/26/35/36, invalid SDK output, exact scope and commands, unsupported split/restore paths, nonzero legacy user rejection, immutable APK-count checks and no single-file partial installation of a split set.
- `DomainTests`: 244 assertions, including explicit legacy builders and modern user-specific command preservation.
- `UiSafetyTests`: 20 assertions for exact session/view-generation binding and operation-scope behavior.
- `ApkManifestInspectorTests`: 20 assertions; ordinary/empty-split base and real split values in UTF-8/UTF-16 pools, malformed/truncated sizes/indices, bounded ZIP manifest reading, and read-only validation against the existing self-built base APK.
- Android Java source compilation uses the existing generated resources and official API35 android.jar, without generating an APK or modifying server assets.

No emulator, physical device, account or remote system action was performed for this change. API19 execution of the new global confirmation, single APK install/uninstall, cancellation/stale dialogs and pre-transfer split/restore failures still belongs to the isolated device acceptance pass. Modern device execution also remains required. Host tests do not establish installer success or parity.
