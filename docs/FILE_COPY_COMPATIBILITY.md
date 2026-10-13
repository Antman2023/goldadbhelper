# Device-local copy compatibility and remaining file-operation boundaries

## Implementation

`MainActivity.paste` uses `FileCopyManager` for copy operations. It no longer invokes `cp -n --`. The manager deploys the existing independently authored helper asset in an exclusively reserved workspace, calls `FileCopyServer`, and parses request-bound, newline-delimited JSON receipts. This works with shell-v1 as well as shell-v2 and does not infer success from an old toolbox command's exit status.

`FileCopyEngine` first enumerates a bounded source plan (at most 10,000 entries and 64 nested levels) without creating a destination. Ordinary files and directories are supported. Unsupported types, unreadable subtrees, failed entries and unexecuted descendants receive distinct per-entry outcomes. It does not follow source-entry symbolic links or copy special-file contents. A directory is complete only when all planned descendants have completed and its source membership still matches the plan.

Source and destination parent directories are opened once and held through `ParcelFileDescriptor` duplicates. Recursive paths are built under `/proc/self/fd/<held-directory>/` using single validated components. A selected parent alias such as `/sdcard` can be resolved when opening the parent; recursive source entries still use no-follow opens. File commits reuse `FileCommitServer` with an additional expected source identity/revision token: regular-file checks, `O_CREAT|O_EXCL` at the actual destination, stable source metadata, SHA-256 and length checks, fsync, and persisted destination hash verification. Existing destinations are never opened for writing.

New directories use exclusive mkdir, then no-follow opening and descriptor anchoring. No existing directory is merged into. Successfully copied files and possibly partial files/directories are retained after errors or cancellation. The implementation never recursively removes an output or its temporary workspace as rollback. Helper cleanup retains the existing fixed-name/empty-directory cleanup policy and surfaces cleanup warnings.

## Accurate cancellation and partial results

Every enumerated entry receives a plan receipt, a start receipt when applicable, and a terminal outcome. The client validates the request ID, source/destination relationship, event transitions, digest form, final entry count and completeness before accepting a successful overall result.

For shell-v2, a known nonzero process exit also prevents overall completion even when a complete receipt was received. The report includes that status and retains verified per-entry outcomes and digests; it does not imply that copied files were rolled back. Shell-v1 has no exit packet, so its unknown status still requires a complete helper receipt. Exit zero alone cannot replace that receipt or turn partial/unknown entries into success.

- Acknowledged files: `COPIED`, with bytes and SHA-256
- Fully acknowledged directories: `DIRECTORY_COPIED`
- Directory with failed/incomplete children: `PARTIAL`
- Failure before a file commit or directory creation was acknowledged: `FAILED`
- Explicitly acknowledged unstarted entries: `NOT_EXECUTED`
- Failed file creation/commit with possible output, or missing terminal receipts: `UNKNOWN`

Closing the client stream does **not** prove remote termination or rollback. The helper attempts to notice a closed receipt channel between work and during hashes/copies, but it may still be completing a write when cancellation is requested. Missing per-entry or final acknowledgements therefore remain UNKNOWN; the user is told to inspect the destination before retrying. Completed per-entry receipts remain completed after a later disconnect. Top-level requests that the client never issued are labelled not executed. The UI records all received outcomes and cleanup warnings in the operation scope, so cancellation does not suppress the result report.

## Explicit limitations

- This is not an atomic tree snapshot or atomically visible copy. Other readers can see partially copied new outputs.
- mkdir does not return a file descriptor. mkdir-to-open cannot atomically prove that an equally privileged concurrent process did not replace the newly created directory. Parent descriptors and no-follow opens narrow the path boundary; they are not a claim of protection against every same-UID/root mutation.
- A confirmation authorizes the listed paths and their contents. Source identities are bound when the remote copy plan is made, after that confirmation; this is not an inode captured when the original clipboard selection was made. Files changed between planning and copying fail verification.
- No preservation of ownership, permission bits, timestamps, xattrs, hardlink relationships or sparse allocation is claimed. New files use the existing helper's 0644 creation mode and new directories use 0700, subject to the target's filesystem/umask rules.
- Missing OEM kernel interfaces, denied directory descriptors or denied `/proc/self/fd` access fail explicitly. No shell overwrite fallback is used.
- Copying a source into its own subtree is refused. Unsupported or unreadable children make the parent partial; they never count as a successful complete directory.
- Move and rename retain the modern `mv -n --` command, now wrapped by `FileMoveServer` and `FileMoveEngine`. Known old targets below API23 receive an explicit Chinese compatibility refusal rather than a fallback that removes `-n`. Before issuing a modern move, the helper records source identity/type and the file digest, and rejects an already-existing exact destination. Afterwards it must observe source absence and the exact destination's original device/inode/type; ordinary files must also match SHA-256 and length. Only that verified outcome clears the clipboard. A skipped command, missing/different exact destination, lost acknowledgement or changed content is UNKNOWN. Cross-filesystem moves are UNKNOWN for both files and directories because inode identity cannot be established, even if a file hash matches. Unsupported source types are refused before issuing the command. Same-filesystem directory identity verification is not a recursive snapshot of its contents. No guarantee of atomic compare-and-rename against equally privileged concurrent changes is claimed, and no claim of complete OEM compatibility follows from SDK version alone.
- Delete still uses the platform `rm -r`, with quoted validated absolute operands and the root/parent-traversal guard. The unsupported old-toolbox `--` separator has been removed; an absolute operand cannot become an option. This is not a descriptor-anchored recursive deletion implementation and does not claim protection against equally privileged concurrent path changes. Ordinary API19 deletion requires separate runtime verification.

## Verification in this change

`bash tools/test-file-copy.sh` passed 49 assertions using ordinary in-memory file/directory fixtures: nested and empty directories, empty and nonempty files, Chinese/space/quote/newline names, existing-target conflicts, retained output after fsync failure, synthetic unsupported type, bounded planning, cancellation before mutation, and cancellation after one acknowledged copied file. These are regression checks, not independent adversarial review.

The same focused script also passed 20 assertions for ordinary move outcomes and synthetic metadata: regular-file and directory success, source still present after a no-clobber skip, missing exact destination, existing target, interrupted command, mismatching content/type, and cross-filesystem identity uncertainty. These tests do not replace emulator execution of `/system/bin/mv`.

`FileCopyCompletionTests` adds 23 host assertions against the actual production report mapper, using pre-populated receipt state: successful/legacy status, nonzero status, retained digests, partial/unexecuted entries, missing acknowledgement and transport errors. It is included in `tools/test-file-copy.sh`. It does not execute Android JSON parsing or device file operations.

The app and server Java sources compile against the installed official Android SDK. `app/src/deviceTest/java/io/github/devicebridge/android/FileCopyJournalProbe.java` now contains 12 synthetic JSON receipt checks for the Android runtime, including nonzero process status and retained digests. Its current source compiles but was not executed in this host-only update; it accesses no files and must be run by the shared emulator-QA owner before being marked passed.

The shared QA owner can run the freshly built helper on a self-created ordinary source tree and a new destination inside its approved emulator fixture root:

`CLASSPATH=<new metadata-server.jar> app_process / io.github.devicebridge.server.FileCopyServer <UUID> <ordinary-source> <new-destination>`

Expect one `plan` and one terminal `result` per enumerated entry, followed by one `done` record. Confirm file bytes independently and retry against the existing destination to verify that it is preserved. Use only the allowed ordinary fixtures. No original APK, real user device, external account, path-replacement, private-file redirection, link attack or escape fixture is required or authorized by this document. Device tests and the final APK hash must be recorded separately; host compilation does not establish API19/modern-device runtime success.
