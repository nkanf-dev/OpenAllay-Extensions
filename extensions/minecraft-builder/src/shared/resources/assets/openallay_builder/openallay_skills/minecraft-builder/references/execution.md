# Online execution

`building.open(options)` obtains a request-bound native session using the
current online Minecraft connection. `dimension` selects the requested current
live dimension; `seed` controls JS random choices. The native context reports
whether execution is available. No method opens region files, level.dat or an
offline world save.

`status()` reports native session state and its journal. `finish()` explicitly
completes the current journal and returns its native status. A successful tool
invocation also finalizes its work automatically; failed or cancelled work
retains its partial status. `list_operations()` lists durable operations.
`cancel()` stops further application. `close()` (alias `save_and_close()`) closes
the online session; it is not an offline save operation.

`undo(operationId?)` requests restoration of a journal in the same live world
incarnation and dimension. World identity uses a live Minecraft SavedData UUID,
not a filesystem path. Undo compares verified postimages before restoration.
Changed cells are conflicts. Ambiguous pending intents from interrupted work
are reported as uncertain and skipped, even if their current value happens to
match the intended value. A failed operation can be undone explicitly as a new
action in the same active session. Inspect restored, conflicting and uncertain
results rather than calling the journal a complete world backup.

Operation journals use the latest strict shape only, with no internal version number:
one initial checkpoint, strictly sequenced atomic intent/outcome delta records per native
quantum, and one final compact checkpoint.
Every intent is forced before its writes. Every completed quantum's actual readback is
forced before the next quantum. Final checkpoint publication precedes removal of
covered deltas. Missing/corrupt sequences fail; recovery never replays world actions.
Malformed or obsolete journals fail validation and remain untouched. There is no
migration reader, automatic deletion or silent fallback.

Generated geometry sends one detached write-region plan per contiguous geometry segment. Bed/door placement, reads and connection repair are explicit segment barriers. Native
work remains sliced by 256 cells without a total-size cap. Plans use memory proportional
to their assignments. A plan is a final-image request: duplicate positions use the last
assignment while preserving the original before-image. Discarded intermediate states
are never placed and therefore do not cause native hooks or physics. Use separate public
operations when ordered intermediate world effects are required. Structure capture uses batched region reads and preserves full
block states and per-cell typed block-entity data. Undo batches both initial comparison
and apply-time comparison; external edits at either point are conflicts, not restoration
targets. Source evidence groups write/readback progress per operation, including verified
partial progress on failure, instead of emitting one source record per block.

JavaScript methods perform synchronous planning and native writes. Native work
runs on the Minecraft owning thread, in cancellable work quanta. Large work may
complete partially before cancellation or a world disconnect. The journal and
native status preserve that distinction. Dimension changes invalidate stale
sessions. Linked blocks and template batches validate their inputs before
application; world mutation failure can still leave a reported partial result.

`update_connections` normalizes native connection shapes in the bounds and
one-block halo after related geometry exists. Changed states use normal native
writes, durable intents and verified readback. It does not notify every air or
interior cell. `sync_physics` is the explicit full-region neighbor and comparator
notification pass, including halo capture, comparison and readback. Both calls
flush pending JS writes before their native work.

This changes the earlier behavior: `update_connections` previously also ran that
full physics pass. Call `sync_physics` explicitly when those whole-region effects
are required. Presets now finish with shape normalization only. The journal owns
explicit block/shape writes, not ordinary physics cascades. Later physics or
external edits can change a verified postimage and produce an undo conflict.

`create(backend,options)` is for deterministic tests or alternate online
adapters. The backend contract requires JSON context/read/readPositions/write/writeRegion/readRegion/scanColumns/probeColumns,
transformState/updateConnections/syncPhysics, status/cancel/close/undo and template storage.
It is not an offline-save adapter. Pure detached tests demonstrate algorithms,
not live-server permissions, loaded chunks, visual quality or loader lifecycle.
