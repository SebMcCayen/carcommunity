package com.kungsbackacarcommunity.app.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OWN-profile badge wall: which rung a member stands on, what the next one
 * costs, and how far along they are toward it.
 *
 * Every case here is pure — no Android, no Firebase. The wall is assembled from
 * the award documents plus the owner's seven ladder counters. Those counters are
 * fetched on the own profile from the owner-only getMyProgress callable (issue
 * #799), so every ladder draws a bar once they resolve; "how far along" is absent
 * only until they load, never fabricated (see [BadgeCounters]).
 */
class BadgeShowcaseTest {

    private fun badge(key: String, awardedAtMillis: Long? = 1_700_000_000_000L) =
        Badge(key = key, fallbackName = null, awardedAtMillis = awardedAtMillis)

    private fun ladderOf(showcase: BadgeShowcase, id: BadgeLadderId): LadderProgress =
        showcase.ladders.first { it.ladder.id == id }

    // -----------------------------------------------------------------------
    // Catalog shape
    // -----------------------------------------------------------------------

    @Test
    fun `catalog is 8 milestones plus 26 ladder rungs`() {
        // 8 standalone: the five original milestones + the three season PODIUM
        // badges (sasong_guld/silver/brons), awarded by rank, not a ladder.
        assertEquals(8, BADGE_MILESTONE_KEYS.size)
        // 26, not 28: Trogen and Samlare have three rungs each (no Platina) —
        // see BadgeLadderCatalogParityTest, which pins this to badge-core.ts.
        assertEquals(26, BADGE_LADDERS.sumOf { it.rungs.size })
        assertEquals(34, BADGE_TOTAL_COUNT)
    }

    @Test
    fun `every ladder rung is reachable from its badge key`() {
        for (ladder in BADGE_LADDERS) {
            for (rung in ladder.rungs) {
                val found = rungForBadgeKey(rung.badgeKey)
                assertEquals(ladder.id, found?.first?.id)
                assertEquals(rung.tier, found?.second?.tier)
            }
        }
        // A standalone milestone belongs to no ladder.
        assertNull(rungForBadgeKey("garage_created"))
        assertNull(rungForBadgeKey("not_a_badge"))
    }

    @Test
    fun `rungs ascend within every ladder`() {
        for (ladder in BADGE_LADDERS) {
            val thresholds = ladder.rungs.map { it.threshold }
            assertEquals(thresholds.sorted(), thresholds)
            val tiers = ladder.rungs.map { it.tier.ordinal }
            assertEquals(tiers.sorted(), tiers)
        }
    }

    // -----------------------------------------------------------------------
    // Zero badges — the motivating empty state
    // -----------------------------------------------------------------------

    @Test
    fun `a member with no badges gets every ladder locked on its first rung`() {
        val showcase = BadgeShowcase.from(badges = emptyList())

        assertFalse(showcase.hasAnyBadge)
        assertEquals(0, showcase.earnedCount)
        assertEquals(34, showcase.totalCount)
        assertTrue(showcase.milestones.isEmpty())
        // All six ladders are still rendered — an empty wall is a menu of goals,
        // never a gap.
        assertEquals(BADGE_LADDERS.size, showcase.ladders.size)

        for (progress in showcase.ladders) {
            assertTrue(progress.isLocked)
            assertNull(progress.highestRung)
            // The medallion depicts the FIRST rung, greyed, with its requirement.
            assertEquals(progress.ladder.rungs.first(), progress.displayRung)
            assertEquals(BadgeTier.BRONS, progress.displayRung.tier)
            assertEquals(progress.ladder.rungs.first(), progress.nextRung)
            assertFalse(progress.isComplete)
        }
    }

    @Test
    fun `until the counters load every ladder offers a goal but no bar`() {
        // Before the getMyProgress callable resolves the counters are all null,
        // so no ladder can draw a bar — but each still shows its next goal.
        val showcase = BadgeShowcase.from(badges = emptyList(), counters = BadgeCounters.NONE)
        for (id in BadgeLadderId.entries) {
            val progress = ladderOf(showcase, id)
            assertNull(progress.observedValue)
            assertNull(progress.fractionToNext)
            // …but the goal itself is always known.
            assertEquals(progress.ladder.rungs.first(), progress.nextRung)
        }
    }

    @Test
    fun `every ladder draws a bar once its counter is present`() {
        // The server hands over all seven counters; each maps to exactly one
        // ladder, so every ladder yields an observed value AND a fraction toward
        // its (unheld) first rung — no ladder is bar-less any more.
        val counters =
            BadgeCounters(
                crownsCollected = 5, // Kronjägare: 5 / 10 to Brons
                lifetimeDistanceMeters = 50_000, // Vägfarare: 50 / 100 km
                verifiedEventsAttended = 3, // Träffräv: 3 / 5 to Silver (Brons=1 held? no badges)
                bestDayStreak = 4, // Trogen
                convoysLed = 1, // Konvojledare
                vehiclesInGarage = 2, // Samlare
                seasonsWon = 1, // Säsongsmästare
            )
        val showcase = BadgeShowcase.from(badges = emptyList(), counters = counters)

        val expected =
            mapOf(
                BadgeLadderId.KRONJAGARE to 5L,
                BadgeLadderId.VAGFARARE to 50_000L,
                BadgeLadderId.TRAFFRAV to 3L,
                BadgeLadderId.TROGEN to 4L,
                BadgeLadderId.KONVOJLEDARE to 1L,
                BadgeLadderId.SAMLARE to 2L,
                BadgeLadderId.SASONGSMASTARE to 1L,
            )
        for ((id, value) in expected) {
            val progress = ladderOf(showcase, id)
            assertEquals(value, progress.observedValue)
            // A bar is drawable: there is a next rung and a fraction toward it.
            assertTrue("$id should still have a next rung", progress.nextRung != null)
            assertTrue("$id should draw a bar", progress.fractionToNext != null)
        }
    }

    // -----------------------------------------------------------------------
    // Highest tier per ladder
    // -----------------------------------------------------------------------

    @Test
    fun `the medallion shows the highest rung held, not the newest`() {
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_guld", awardedAtMillis = 1L),
                        badge("kronjagare_brons", awardedAtMillis = 9_999L),
                        badge("kronjagare_silver", awardedAtMillis = 500L),
                    ),
            )
        val kronjagare = ladderOf(showcase, BadgeLadderId.KRONJAGARE)

        assertEquals(BadgeTier.GULD, kronjagare.highestRung?.tier)
        assertEquals(BadgeTier.GULD, kronjagare.displayRung.tier)
        assertFalse(kronjagare.isLocked)
        assertEquals(3, kronjagare.earnedRungs.size)
        assertEquals(BadgeTier.PLATINA, kronjagare.nextRung?.tier)
    }

    @Test
    fun `a gap left by a partial write is offered again rather than skipped`() {
        // The monotonic backend never produces this, but a partial write might.
        val showcase = BadgeShowcase.from(badges = listOf(badge("traffrav_guld")))
        val traffrav = ladderOf(showcase, BadgeLadderId.TRAFFRAV)

        assertEquals(BadgeTier.GULD, traffrav.highestRung?.tier)
        assertEquals(BadgeTier.BRONS, traffrav.nextRung?.tier)
        assertFalse(traffrav.isLocked)
    }

    @Test
    fun `unknown and duplicate keys never inflate the unlocked count`() {
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons"),
                        badge("kronjagare_brons"),
                        badge("a_badge_from_the_future"),
                        badge("garage_created"),
                    ),
            )
        assertEquals(2, showcase.earnedCount)
        assertEquals(listOf("garage_created"), showcase.milestones.map { it.key })
    }

    // -----------------------------------------------------------------------
    // Fully completed ladders
    // -----------------------------------------------------------------------

    @Test
    fun `a Platina ladder is complete and draws no bar`() {
        val kronjagare = ladderById(BadgeLadderId.KRONJAGARE)
        val showcase =
            BadgeShowcase.from(
                badges = kronjagare.badgeKeys.map { badge(it) },
                // Even with a counter present, a finished ladder has no next rung
                // and therefore no fraction to fill.
                counters = BadgeCounters(crownsCollected = 1_000, vehiclesInGarage = 5),
            )
        val progress = ladderOf(showcase, BadgeLadderId.KRONJAGARE)

        assertTrue(progress.isComplete)
        assertNull(progress.nextRung)
        assertNull(progress.fractionToNext)
        assertEquals(BadgeTier.PLATINA, progress.highestRung?.tier)
        // A complete ladder drops out of the "next tier" band entirely.
        assertFalse(showcase.laddersInProgress.any { it.ladder.id == BadgeLadderId.KRONJAGARE })
    }

    @Test
    fun `Samlare tops out at Guld — its Platina rung would be unreachable`() {
        val samlare = ladderById(BadgeLadderId.SAMLARE)
        assertEquals(3, samlare.rungs.size)
        assertEquals(BadgeTier.GULD, samlare.rungs.last().tier)

        val showcase =
            BadgeShowcase.from(
                badges = samlare.badgeKeys.map { badge(it) },
                counters = BadgeCounters(vehiclesInGarage = 5),
            )
        val progress = ladderOf(showcase, BadgeLadderId.SAMLARE)
        assertTrue(progress.isComplete)
        assertNull(progress.fractionToNext)
    }

    // -----------------------------------------------------------------------
    // The climb: counter → current tier, next threshold, fraction
    // -----------------------------------------------------------------------

    @Test
    fun `distance progress is measured from zero to the next threshold`() {
        // 234 km driven: Brons (100 km) is held, Silver (500 km) is next.
        val showcase =
            BadgeShowcase.from(
                badges = listOf(badge("vagfarare_brons")),
                counters = BadgeCounters(lifetimeDistanceMeters = 234_000),
            )
        val vagfarare = ladderOf(showcase, BadgeLadderId.VAGFARARE)

        assertEquals(BadgeTier.BRONS, vagfarare.highestRung?.tier)
        assertEquals(BadgeTier.SILVER, vagfarare.nextRung?.tier)
        assertEquals(500_000L, vagfarare.nextRung?.threshold)
        assertEquals(234_000L, vagfarare.observedValue)
        assertEquals(234_000f / 500_000f, vagfarare.fractionToNext!!, 0.0001f)
        // Both sides of the "234 km / 500 km" line render in the same unit.
        assertEquals("234 km", formatLadderValue(vagfarare.ladder.unit, vagfarare.observedValue!!))
        assertEquals("500 km", formatLadderValue(vagfarare.ladder.unit, vagfarare.nextRung!!.threshold))
    }

    @Test
    fun `a count ladder reads its own unit without a suffix`() {
        val showcase =
            BadgeShowcase.from(badges = emptyList(), counters = BadgeCounters(vehiclesInGarage = 2))
        val samlare = ladderOf(showcase, BadgeLadderId.SAMLARE)

        assertEquals(2L, samlare.observedValue)
        assertEquals(BadgeTier.BRONS, samlare.nextRung?.tier)
        assertEquals("2", formatLadderValue(samlare.ladder.unit, 2L))
        // Brons needs 1 vehicle and 2 are held — the server simply has not
        // evaluated yet, so the bar saturates rather than overflowing.
        assertEquals(1f, samlare.fractionToNext!!, 0.0001f)
    }

    @Test
    fun `a stray negative counter is ignored rather than drawn`() {
        // The callable sanitises server-side, but the model still floors out a
        // negative as defence — it reads as "not observed", never a below-zero
        // bar. (NaN/Infinity/non-numeric are impossible here — the fields are
        // Long — and are handled by BadgeProgressResponseParser; see its test.)
        val showcase =
            BadgeShowcase.from(
                badges = emptyList(),
                counters =
                    BadgeCounters(
                        lifetimeDistanceMeters = -1,
                        vehiclesInGarage = -3,
                    ),
            )
        assertNull(ladderOf(showcase, BadgeLadderId.VAGFARARE).observedValue)
        assertNull(ladderOf(showcase, BadgeLadderId.VAGFARARE).fractionToNext)
        assertNull(ladderOf(showcase, BadgeLadderId.SAMLARE).observedValue)
    }

    @Test
    fun `the climb list leads with the ladders that have a real bar`() {
        val showcase =
            BadgeShowcase.from(
                badges = emptyList(),
                // Only two counters are known here; the rest are still null, so
                // this exercises the ordering between ladders that have a bar and
                // ladders that (for now) do not.
                counters = BadgeCounters(lifetimeDistanceMeters = 90_000, vehiclesInGarage = 0),
            )
        val order = showcase.laddersInProgress.map { it.ladder.id }

        // Vägfarare is 90 % of the way to Brons, Samlare 0 % — both have a bar,
        // so both lead the ladders whose counter has not loaded, most-complete
        // first.
        assertEquals(BadgeLadderId.VAGFARARE, order.first())
        assertEquals(BadgeLadderId.SAMLARE, order[1])
        // The remainder keeps catalog order so the list never reshuffles.
        assertEquals(
            listOf(
                BadgeLadderId.KRONJAGARE,
                BadgeLadderId.TRAFFRAV,
                BadgeLadderId.TROGEN,
                BadgeLadderId.KONVOJLEDARE,
                BadgeLadderId.SASONGSMASTARE,
            ),
            order.drop(2),
        )
    }

    @Test
    fun `award dates are carried through for the detail sheet`() {
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons", awardedAtMillis = 42L),
                        badge("first_event", awardedAtMillis = null),
                    ),
            )
        assertEquals(42L, showcase.awardedAtByKey["kronjagare_brons"])
        // An undated award simply has no entry — never a fabricated date.
        assertNull(showcase.awardedAtByKey["first_event"])
    }

    // -----------------------------------------------------------------------
    // The summary strip: last acquired awards, count-consistent
    // -----------------------------------------------------------------------

    @Test
    fun `the summary lists the most recently acquired awards, newest first, milestones included`() {
        // Two tiers of ONE ladder plus a milestone plus another ladder's tier —
        // the exact shape that used to show fewer medallions than the count.
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons", awardedAtMillis = 100L),
                        badge("garage_created", awardedAtMillis = 200L),
                        badge("kronjagare_silver", awardedAtMillis = 300L),
                        badge("vagfarare_brons", awardedAtMillis = 400L),
                    ),
            )

        // Four earned, fewer than the cap → the strip shows all four, so its size
        // equals the "x of 34" numerator. This is the reported bug fixed.
        assertEquals(4, showcase.earnedCount)
        assertEquals(showcase.earnedCount, showcase.recentAwards.size)

        // Newest acquired first.
        assertEquals(
            listOf("vagfarare_brons", "kronjagare_silver", "garage_created", "kronjagare_brons"),
            showcase.recentAwards.map { it.badgeKey },
        )
        // The milestone is in the strip (it never appeared in the old per-ladder grid)…
        assertTrue(showcase.recentAwards.any { it.badgeKey == "garage_created" && it.isMilestone })
        // …and BOTH tiers of the same ladder are their own items (no collapse).
        assertEquals(
            2,
            showcase.recentAwards.count { it.ladderId == BadgeLadderId.KRONJAGARE },
        )
    }

    @Test
    fun `the summary shows at most six, keeping the six most recently acquired`() {
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons", awardedAtMillis = 10L),
                        badge("kronjagare_silver", awardedAtMillis = 20L),
                        badge("kronjagare_guld", awardedAtMillis = 30L),
                        badge("traffrav_brons", awardedAtMillis = 40L),
                        badge("traffrav_silver", awardedAtMillis = 50L),
                        badge("traffrav_guld", awardedAtMillis = 60L),
                        badge("garage_created", awardedAtMillis = 70L),
                    ),
            )

        assertEquals(7, showcase.earnedCount)
        assertEquals(BadgeShowcase.RECENT_AWARDS_LIMIT, showcase.recentAwards.size)
        // The single oldest award (t=10) is the one dropped by the cap.
        assertFalse(showcase.recentAwards.any { it.badgeKey == "kronjagare_brons" })
        assertEquals("garage_created", showcase.recentAwards.first().badgeKey)
    }

    @Test
    fun `an undated award sorts after every dated one`() {
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons", awardedAtMillis = null),
                        badge("vagfarare_brons", awardedAtMillis = 100L),
                        badge("garage_created", awardedAtMillis = 200L),
                    ),
            )
        // Dated awards lead, newest first; the undated one is last, never first.
        assertEquals(
            listOf("garage_created", "vagfarare_brons", "kronjagare_brons"),
            showcase.recentAwards.map { it.badgeKey },
        )
    }

    @Test
    fun `unknown and duplicate keys never reach the summary and never break count parity`() {
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons", awardedAtMillis = 100L),
                        badge("kronjagare_brons", awardedAtMillis = 100L),
                        badge("a_badge_from_the_future", awardedAtMillis = 999L),
                        badge("garage_created", awardedAtMillis = 200L),
                    ),
            )
        // The future/unknown key is neither counted nor shown; the duplicate collapses.
        assertEquals(2, showcase.earnedCount)
        assertEquals(showcase.earnedCount, showcase.recentAwards.size)
        assertFalse(showcase.recentAwards.any { it.badgeKey == "a_badge_from_the_future" })
    }

    @Test
    fun `a member with no badges has an empty summary strip`() {
        val showcase = BadgeShowcase.from(badges = emptyList())
        assertTrue(showcase.recentAwards.isEmpty())
        assertEquals(showcase.earnedCount, showcase.recentAwards.size)
    }

    @Test
    fun `a duplicated key collapses to its newest doc, never an older or undated one`() {
        // The backend never writes a key twice (the doc id IS the key), but if it
        // ever did, the recency strip and the detail date must both take the
        // NEWEST occurrence — not the first-seen, older, or undated one.
        val showcase =
            BadgeShowcase.from(
                badges =
                    listOf(
                        badge("kronjagare_brons", awardedAtMillis = null),
                        badge("kronjagare_brons", awardedAtMillis = 100L),
                        badge("kronjagare_brons", awardedAtMillis = 900L),
                        badge("vagfarare_brons", awardedAtMillis = 500L),
                    ),
            )

        // Two distinct keys held; the three kronjagare_brons docs collapse to one.
        assertEquals(2, showcase.earnedCount)
        assertEquals(showcase.earnedCount, showcase.recentAwards.size)

        // kronjagare_brons acquired at 900 is NEWER than vagfarare_brons at 500,
        // so it leads — proving the survivor is the newest doc, not the first.
        assertEquals(
            listOf("kronjagare_brons", "vagfarare_brons"),
            showcase.recentAwards.map { it.badgeKey },
        )
        // The detail-sheet date agrees: newest timestamp per key, never null.
        assertEquals(900L, showcase.awardedAtByKey["kronjagare_brons"])
    }
}
