package com.crispy.tv.contracts

/**
 * One contract fixture, as it exists in `contracts/fixtures/`.
 *
 * Instances come from the generated `ContractFixtures` object, so a fixture
 * always knows its own location without anyone passing a path around. That is
 * the reason this type exists: it replaced `java.nio.file.Path`, which the suite
 * used purely to name the offending fixture in a failure message, and which
 * cannot exist on Kotlin/Native at all.
 *
 * The path is repository-relative with `/` separators, e.g.
 * `player_machine/v2/opens_http.json`.
 */
internal class ContractFixture(val path: String, val json: String) {

    companion object {
        /**
         * A name for an assertion that is not tied to a fixture file.
         *
         * A few checks compare a field against a JSON object without iterating
         * fixtures, so they have a label rather than a path to identify
         * themselves. They still go through the same `require*` helpers, and
         * those need something to name the failure — `null` in an error message
         * is strictly worse than the label they already have.
         */
        fun displayName(name: String): ContractFixture = ContractFixture(name, json = "")
    }

    /**
     * The suite this fixture belongs to: the first path segment, e.g.
     * `player_machine`.
     *
     * This is what a fixture's `suite` field must equal, and
     * `ContractFixturesSanityTest` asserts that it does.
     */
    val suite: String get() = path.substringBefore('/')

    /**
     * The versioned directory, e.g. `player_machine/v2`.
     *
     * Every suite has exactly one version directory today, and the sanity test
     * enforces that. A suite that needs a second one is a spec revision, not a
     * migration, and should be noticed rather than silently merged.
     */
    val versionDirectory: String get() = path.substringBeforeLast('/')

    /**
     * Renders as the repository-relative path, so string interpolation in an
     * assertion message produces `player_machine/v2/opens_http.json: phase
     * mismatch` with no further ceremony. This is what the old
     * `Path.toDisplayPath()` existed to do.
     */
    override fun toString(): String = path

    override fun equals(other: Any?): Boolean = other is ContractFixture && other.path == path

    override fun hashCode(): Int = path.hashCode()
}
