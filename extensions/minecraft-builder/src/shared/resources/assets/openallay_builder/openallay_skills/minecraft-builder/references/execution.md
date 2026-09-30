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

JavaScript methods perform synchronous planning and native writes. Native work
runs on the Minecraft owning thread, in cancellable work quanta. Large work may
complete partially before cancellation or a world disconnect. The journal and
native status preserve that distinction. Dimension changes invalidate stale
sessions. Linked blocks and template batches validate their inputs before
application; world mutation failure can still leave a reported partial result.

`update_connections` uses native shape, neighbor and comparator rules rather
than a guessed list of solid materials. It includes boundary neighbors. Run it
after related geometry exists; this avoids updating an incomplete door, bed or
support stack. The journal owns explicit block/shape writes with immediate
readback. It does not retroactively own ordinary physics cascades. Later physics
or external edits can change a verified postimage and produce an undo conflict.

`create(backend,options)` is for deterministic tests or alternate online
adapters. The backend contract is JSON context/read/write/writeRegion,
transformState/updateConnections, status/cancel/close/undo and template storage.
It is not an offline-save adapter. Pure detached tests demonstrate algorithms,
not live-server permissions, loaded chunks, visual quality or loader lifecycle.
