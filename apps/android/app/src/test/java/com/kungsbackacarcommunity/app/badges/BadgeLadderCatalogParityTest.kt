package com.kungsbackacarcommunity.app.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The MIRROR CONTRACT: [BADGE_LADDERS] is a client-side copy of the backend
 * catalog in `functions/src/badges/badge-core.ts`, and this test is what keeps
 * the copy honest.
 *
 * Drift here is not a crash — it is a wrong GOAL LINE on a member's profile
 * ("collect 50 crowns" when the server wants 60), which nothing else would
 * catch: qualification is decided entirely server-side, so the app would keep
 * showing the wrong target indefinitely while awarding correctly. A doc comment
 * saying "copied verbatim" cannot enforce that. This does: any PR that changes a
 * threshold, a badge key or a rung on either side without changing the other
 * fails here.
 *
 * Reads the TypeScript as a FILE so it stays a plain JVM unit test — no Node, no
 * build step, no generated artefact to keep in sync.
 */
class BadgeLadderCatalogParityTest {

    private data class TsRung(val tier: String, val key: String, val threshold: Long)

    @Test
    fun `the Kotlin ladder mirror matches the backend catalog exactly`() {
        val backend = parseBackendLadders()

        assertEquals(
            "ladder set differs between badge-core.ts and BadgeLadders.kt",
            backend.keys.sorted(),
            BADGE_LADDERS.map { it.id.key }.sorted(),
        )

        for (ladder in BADGE_LADDERS) {
            val expected =
                backend[ladder.id.key] ?: error("ladder ${ladder.id.key} is missing from badge-core.ts")
            assertEquals(
                "rung count differs for ${ladder.id.key}",
                expected.size,
                ladder.rungs.size,
            )
            for ((index, rung) in ladder.rungs.withIndex()) {
                val expectedRung = expected[index]
                assertEquals("tier ${index + 1} of ${ladder.id.key}", expectedRung.tier, rung.tier.key)
                assertEquals("key of ${expectedRung.tier} ${ladder.id.key}", expectedRung.key, rung.badgeKey)
                assertEquals(
                    "threshold of ${expectedRung.tier} ${ladder.id.key}",
                    expectedRung.threshold,
                    rung.threshold,
                )
            }
        }
    }

    /**
     * NO SPEED IMAGERY — the standing product stance, mirrored on the client.
     *
     * The backend enforces this on its written art briefs; the Android medallion
     * is where the art actually gets DRAWN, so the same prohibition is enforced
     * on that source file. A term may appear only inside an explicit "no <term>"
     * prohibition.
     */
    @Test
    fun `the medallion art never depicts speed`() {
        val source = medallionSource().lowercase()
        for (term in listOf("speedometer", "motion line", "speed line", "chequered", "checkered", "needle", "racing")) {
            val mentions = source.split(term).size - 1
            val prohibitions = source.split("no $term").size - 1
            assertEquals(
                "'$term' appears in BadgeMedallion.kt outside a 'no $term' prohibition",
                prohibitions,
                mentions,
            )
        }
        // Vägfarare is the glyph most at risk of drifting into speed imagery, so
        // its own comment must state the prohibition outright.
        val vagfarare =
            source.substringAfter("vägfarare — a road ribbon")
                .substringBefore("private fun drawscope.drawfoxglyph")
        assertTrue("the Vägfarare brief must say it never depicts speed", vagfarare.contains("never speed"))
        assertTrue(vagfarare.contains("no speedometer"))
        assertTrue(vagfarare.contains("no motion line"))
    }

    // -----------------------------------------------------------------------
    // Backend catalog parsing
    // -----------------------------------------------------------------------

    /** ladder key → its rungs, bottom-to-top, as declared in badge-core.ts. */
    private fun parseBackendLadders(): Map<String, List<TsRung>> {
        val source = backendCatalogSource()
        val body =
            source.substringAfter("export const BADGE_LADDERS", "")
                .ifEmpty { error("BADGE_LADDERS not found in badge-core.ts") }

        val ladderPattern = Regex("""ladder:\s*'([a-z]+)'""")
        val rungPattern =
            Regex("""\{\s*tier:\s*'(\w+)',\s*key:\s*'(\w+)',\s*threshold:\s*([0-9_]+(?:\s*\*\s*METRES_PER_KM)?)\s*}""")

        val ladderStarts = ladderPattern.findAll(body).toList()
        assertTrue("no ladders parsed out of badge-core.ts", ladderStarts.isNotEmpty())

        return ladderStarts.mapIndexed { index, match ->
            val end = ladderStarts.getOrNull(index + 1)?.range?.first ?: body.length
            val slice = body.substring(match.range.last, end)
            val rungs =
                rungPattern.findAll(slice).map { rung ->
                    TsRung(
                        tier = rung.groupValues[1],
                        key = rung.groupValues[2],
                        threshold = parseThreshold(rung.groupValues[3]),
                    )
                }.toList()
            assertTrue("no rungs parsed for ladder ${match.groupValues[1]}", rungs.isNotEmpty())
            match.groupValues[1] to rungs
        }.toMap()
    }

    /**
     * `100 * METRES_PER_KM` → 100 000; `1_000` → 1000.
     *
     * The unit factor is stripped BEFORE the digit separators are: the constant's
     * own name contains underscores, so removing them first would mangle it.
     */
    private fun parseThreshold(raw: String): Long {
        val compact = raw.replace(" ", "")
        val inKilometres = compact.endsWith("*METRES_PER_KM")
        val digits = compact.removeSuffix("*METRES_PER_KM").replace("_", "")
        return digits.toLong() * (if (inKilometres) 1_000L else 1L)
    }

    // -----------------------------------------------------------------------
    // Source lookup
    // -----------------------------------------------------------------------

    private fun backendCatalogSource(): String =
        readRepoFile("functions/src/badges/badge-core.ts")

    private fun medallionSource(): String =
        readRepoFile(
            "apps/android/app/src/main/java/com/kungsbackacarcommunity/app/badges/BadgeMedallion.kt",
        )

    /**
     * Gradle runs unit tests with the module dir as the working dir, but do not
     * depend on it: walk up until the repository root (the dir holding both
     * `functions/` and `apps/`) appears.
     */
    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("could not locate $relativePath from ${File("").absolutePath}")
    }
}
