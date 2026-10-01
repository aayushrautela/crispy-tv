package com.crispy.tv.optimistic

import kotlin.uuid.Uuid

/**
 * Mints the identifier a [UserMutation] is stored under.
 *
 * **It is in `commonMain` because the premise that kept it in `androidMain` was false, and
 * the false premise was the only reason.** It used to read *"`UUID` is a JDK class with no
 * Kotlin/Native equivalent"*, which was true when written and is not true of the toolchain
 * this repository builds with. Measured against the resolved artifact rather than against
 * the family: Kotlin is 2.4.10, and
 * `kotlin-stdlib-2.4.10.jar` contains a full `kotlin/uuid/` package. `Uuid.random()` is
 * **stable** there — the class carries `kotlin.WasExperimental`, and of the companion's
 * members only `generateV4` still carries `kotlin.uuid.ExperimentalUuidApi` (with
 * `SinceKotlin 2.3`) — so it needs no `@OptIn` at all. `kotlin.uuid` is a `kotlin.*` stdlib
 * package, so it is in every stdlib including Kotlin/Native, and no dependency was added.
 *
 * **The callers, not the outbox, still create the ids, and the slots are unchanged.**
 * `DetailsViewModel` takes a required `newMutationId: () -> String` and
 * `LibraryScreen` takes one under the same name, both so their own ids stay
 * deterministic under test and neither ever names this function in an assertion.
 * `UserMutationIdsTest` is what pins the thing the slots cannot: the *format*.
 */
fun newUserMutationId(): String = Uuid.random().toString()
