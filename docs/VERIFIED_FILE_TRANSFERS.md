# Verified file-transfer API and safety model

This implementation is independently authored. It does not use an original APK,
its resources, or an original app's implementation. The remote helper is bundled
into the project's existing `metadata-server.jar` by `tools/build-servers.sh`.

## Android API

Construct `new FileTransferManager(context, adbSession)` off the UI thread and call:

- `upload(File local, String absoluteRemotePath, SyncClient.Progress progress)`
- `download(String absoluteRemotePath, OutputStream output, SyncClient.Progress progress)`

Both return `FileTransferManager.Report`, inherited from the portable
`VerifiedFileTransfer` implementation. It contains `bytes`, lowercase `sha256`, and
an immutable `cleanupWarnings` list. A successful report means the file transfer
and verification completed. Cleanup warnings must still be shown to the user.
Neither method closes the caller's local download output stream or deletes the
caller-owned upload source. A failed download's caller owns cleanup of newly
created SAF/private-cache output. A direct local final-path output is retained
for inspection on failure rather than risking a delete-after-replacement race.
Any retained partial output or unconfirmed cleanup must be reported.

Failures throw `FileTransferManager.TransferException` (an `IOException`), with:

- `cancelled`: cancellation was requested or an `InterruptedIOException` occurred
- `outcome`: `NOT_COMMITTED`, `COMMITTED`, or `UNKNOWN`
- `cleanupWarnings`: temporary cleanup errors
- `remotePath`: the requested remote source/destination

`UNKNOWN` is deliberately conservative: the commit reply could be lost, or the
helper may have created a partial destination. Never blindly retry or tell the
user that no change occurred. Inspect the exact destination before any further
action. `COMMITTED` on a cancellation means a verified success acknowledgement
arrived before cancellation was observed. Keep this per-item result available
even if the activity navigates away or a Future is cancelled.

## Upload protocol

1. Validate absolute paths as strict UTF-8, with the 1024-byte SYNC path limit.
   Unicode, spaces, quotes, and literal newlines remain path data.
2. Hash and snapshot the local regular file. Refuse symlinks/nonregular sources.
3. Reserve `/data/local/tmp/goldenbeetle-transfer-<UUID>` with exclusive `mkdir`
   under umask 077. Each operation has its own directory, even on one session.
   An unacknowledged reservation is not treated as proof of ownership.
4. Register the helper path **before** its first push; deploy the bundled helper
   there and mark its dex JAR read-only. Register payload **before** its push.
5. Push only the temporary payload. Rehash/resnapshot the local source; reject
   source mutation or a short push before any final-target command.
6. Invoke the helper with separately shell-quoted arguments. The helper checks
   the staged length and SHA-256 and opens the **actual final destination** with
   kernel `open(O_CREAT | O_EXCL | O_WRONLY, 0644)` with close-on-exec.
   API21+ uses public android.system.Os; API19/20 uses the equivalent libcore
   interface and fcntl(F_SETFD, FD_CLOEXEC).
   Existing files, directories, and symlinks are never replaced or truncated.
7. Copy with explicit partial-write handling, verify the copied digest and stable
   source identity/timestamps, fsync the destination, then re-read/hash the
   persisted destination and check its identity. Return a versioned acknowledgement.
8. In finally, remove only whitelisted ART oat byproducts through no-follow,
   descriptor-anchored directories, then clean registered workspace files and
   non-recursively rmdir the workspace. Cleanup calls have short bounded ADB idle
   timeouts. Clear cancellation interruption only for cleanup, then restore it.

### Important final-path failure boundary

Exclusive creation prevents overwrite, but copying is not an atomic publication:
other readers can see a newly created partial destination. If anything fails
after exclusive creation, the helper retains that possible partial destination
and returns `UNKNOWN` with its path. Device/process death has the same uncertainty.

The helper deliberately **does not unlink the final target on failure**. Checking
its inode with lstat and then unlinking is a TOCTOU race: another process can
replace the name between those syscalls, causing deletion of its file. Android's
public path API supplies no atomic conditional unlink. The private temporary
workspace is cleaned separately. This is not a sandbox against malicious processes
sharing the ADB shell UID or a malicious/compromised device.

No shell `test ! -e`, `mv -n`, or `cp -n` is used to guarantee upload exclusion.
No success is inferred from a missing shell status or malformed/duplicate helper
response. Shell-v2 uses its protocol exit packet; older one-shot shells use a
unique trailing exit acknowledgement, validated before reporting completion.
The interactive raw shell is separate and does not invent exit status.

## Download protocol

The helper's `hash` opens with no symlink following and nonblocking open, then
requires a regular file. It compares pre/post fstat plus path lstat using device,
inode, size, mtime, and ctime. API 27+ timestamps have nanosecond precision;
API19-26 expose second-resolution timestamps, so metadata alone cannot
detect same-second same-size mutations. Content hashes before/after transfer and
the actual copied/received-byte digest still must match. The helper explicitly
requires a remote device running API19 or newer and fails closed if its kernel
interface is unavailable. The client hashes the bytes received via
SYNC and compares both length and SHA-256 to stable helper hashes taken before
and after download. The source snapshots must also match across the transfer.
Source replacement, mutation, short reads, mismatched bytes, and cancellation are
failures. Symlinks and special files are intentionally rejected.

## Local verification

The tests are synthetic and use no real devices. Use the official installed SDK:

```sh
SDK=/workspace/shared/device_bridge_toolchain/android-sdk
mkdir -p build/transfer-classes build/transfer-server-classes
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options \
  -d build/transfer-classes $(find core/src/main/java -name '*.java') \
  core/src/test/java/io/github/devicebridge/core/FileTransferTests.java
java -ea -cp build/transfer-classes io.github.devicebridge.core.FileTransferTests
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options \
  -cp "$SDK/platforms/android-35/android.jar" -d build/transfer-server-classes \
  server/src/io/github/devicebridge/server/FileCommitServer.java \
  core/src/main/java/io/github/devicebridge/core/AndroidFileAccess.java \
  server/test/io/github/devicebridge/server/FileCommitServerTests.java
java -ea -cp "build/transfer-server-classes:$SDK/platforms/android-35/android.jar" \
  io.github.devicebridge.server.FileCommitServerTests
```

Core suite: 19 cases, including existing/racing destination, partial helper/payload
cleanup, cleanup warnings, corrupt stage, lost commit reply, callback/interrupt
cancellation and outcomes, changed local/remote sources, download mismatch, invalid
Unicode/byte lengths, unique workspaces, and cancellation during cleanup.

Server suite: 17 cases, including existing file/symlink, competing create,
stage mismatch, write/fsync/close failures, destination replacement, unsafe
conditional-delete regression, source mutation, persisted corruption,
partial-write loops, and invalid Unicode/NUL. The retained-target regression covers the old lstat-to-unlink bug. These
focused fixtures are not a claim of a completed independent security review. Compilation and synthetic tests do not replace Android runtime testing.

## Primary API references

- [Android Os.open](https://developer.android.com/reference/android/system/Os#open(java.lang.String,int,int))
- [Android StructStat and API levels](https://developer.android.com/reference/android/system/StructStat#st_mtim)
- [Android 14 dynamic code loading](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)
