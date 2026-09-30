/**
 * Detached online-builder persistence. These stores operate only on dedicated Extension application
 * directories, never region files, level.dat or another Minecraft save artifact. All calls must run
 * on the worker, not a Minecraft owner thread. The native adapter owns registry validation and SNBT
 * parsing; this package preserves namespaced IDs, property strings and opaque SNBT without registry
 * substitution or content loss.
 *
 * <p>Publication forces temporary-file contents and uses an atomic same-directory rename. Unsupported
 * atomic moves fail; no non-atomic fallback is used. Directory metadata is forced on supported POSIX
 * filesystems. Unsupported directory synchronization (including the Windows Java directory-channel
 * limitation) does not fail ordinary storage. Real IO failures do fail. This is process-interruption
 * protection, not a universal power-loss guarantee for every operating system, device or filesystem.
 *
 * <p>One OperationJournal instance owns a directory for a running client. Restart marks abandoned
 * RUNNING snapshots INTERRUPTED, without replay or rollback. Undo requires a fresh authorized native
 * operation, matching world/dimension identity, and an owner-thread compare-and-write against each
 * verified entry's expected postimage. Default undo skips unverified pending entries as uncertain,
 * even when current equals intended. Explicitly known-unstarted intents are durably aborted rather
 * than mistaken for uncertain effects. The journal does not restore entities, neighboring simulation effects,
 * or other edits outside its recorded block positions.
 */
package dev.openallay.builder.storage;
