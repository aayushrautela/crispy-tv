package com.crispy.tv.optimistic

import java.util.UUID

/**
 * Mints the identifier a [UserMutation] is stored under.
 *
 * It lives here, in `androidMain`, rather than on the outbox or in `commonMain`, for two
 * measured reasons. `UUID` is a JDK class with no Kotlin/Native equivalent, and the
 * callers — not the outbox — are what create the ids, so a generator parked on the class
 * was a shared helper sitting on the wrong type. `DetailsViewModel` takes a
 * `newMutationId: () -> String` so its own ids stay deterministic under test and never
 * name this function; `LibraryScreen` calls it directly, because it is already in
 * `androidMain` and has nothing to inject into.
 */
fun newUserMutationId(): String = UUID.randomUUID().toString()
