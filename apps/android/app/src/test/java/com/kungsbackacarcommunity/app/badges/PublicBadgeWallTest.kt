package com.kungsbackacarcommunity.app.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANOTHER member's badge wall: publish the trophies, not the telemetry.
 *
 * Badges are public (firebase/firestore.rules lets any authenticated user read
 * `users/{uid}/badges`) so achievements can be shown off. The counters they were
 * earned against stay on the backend-only `badgeProgress/{uid}`. These tests pin
 * the CLIENT half of that boundary: [PublicBadgeWall] must expose the rungs a
 * member reached and nothing about how far along they are.
 */
class PublicBadgeWallTest {

    private fun badge(key: String, awardedAtMillis: Long? = 1_700_000_000_000L) =
        Badge(key = key, fallbackName = null, awardedAtMillis = awardedAtMillis)

    // -----------------------------------------------------------------------
    // The privacy boundary, asserted structurally
    // -----------------------------------------------------------------------

    /**
     * The strongest form of the guarantee: there is nowhere on the public model
     * to PUT a progress number. A future edit that adds a counter, a next rung
     * or a fraction to another member's wall fails here, not in review.
     */
    @Test
    fun `the public wall has no field that could carry progress`() {
        val forbidden =
            listOf(
                "counter",
                "observed",
                "next",
                "fraction",
                "progress",
                "streak",
                "distance",
                "remaining",
                "target",
                "threshold",
            )
        // Plain Java reflection — no kotlin-reflect dependency needed. A data
        // class's constructor properties are its declared fields; synthetic
        // compiler fields (`$stable`) are filtered out.
        val propertyNames =
            (
                PublicBadgeWall::class.java.declaredFields.toList() +
                    PublicLadderStanding::class.java.declaredFields.toList()
                )
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name.lowercase() }

        assertTrue("reflection found no fields — the guard would pass vacuously", propertyNames.isNotEmpty())
        for (name in propertyNames) {
            for (term in forbidden) {
                assertFalse(
                    "PublicBadgeWall/PublicLadderStanding exposes '$name' — another member's " +
                        "profile must show trophies only, never progress.",
                    name.contains(term),
                )
            }
        }
    }

    /**
     * The counters cannot be smuggled in either: [PublicBadgeWall.from] takes the
     * award list and nothing else, so no call site can hand it a [BadgeCounters]
     * the way the own-profile [BadgeShowcase.from] deliberately does.
     */
    @Test
    fun `building a public wall accepts nothing but the awards`() {
        val builders =
            PublicBadgeWall.Companion::class.java.declaredMethods.filter { it.name == "from" }
        assertEquals(1, builders.size)
        assertEquals(listOf(List::class.java), builders.single().parameterTypes.toList())

        // Contrast: the OWN-profile builder does take counters, which is exactly
        // the asymmetry this whole file exists to hold in place.
        assertTrue(
            BadgeShowcase.Companion::class.java.declaredMethods
                .filter { it.name == "from" }
                .any { it.parameterTypes.contains(BadgeCounters::class.java) },
        )
    }

    // -----------------------------------------------------------------------
    // What it does show
    // -----------------------------------------------------------------------

    @Test
    fun `it shows the top rung reached on every started ladder`() {
        val wall =
            PublicBadgeWall.from(
                listOf(
                    badge("kronjagare_brons"),
                    badge("kronjagare_silver", awardedAtMillis = 777L),
                    badge("samlare_brons"),
                ),
            )

        assertTrue(wall.hasAnyBadge)
        assertEquals(3, wall.earnedCount)
        assertEquals(28, wall.totalCount)
        assertEquals(
            listOf(BadgeLadderId.KRONJAGARE, BadgeLadderId.SAMLARE),
            wall.ladders.map { it.ladder.id },
        )

        val kronjagare = wall.ladders.first { it.ladder.id == BadgeLadderId.KRONJAGARE }
        assertEquals(BadgeTier.SILVER, kronjagare.highestRung.tier)
        assertEquals(777L, kronjagare.awardedAtMillis)
    }

    @Test
    fun `ladders they have not started are omitted, not greyed out`() {
        // A stranger's profile is a showcase, not a list of what they have not
        // done — the locked/greyed rungs belong on your OWN wall as motivation.
        val wall = PublicBadgeWall.from(listOf(badge("trogen_brons")))

        assertEquals(1, wall.ladders.size)
        assertEquals(BadgeLadderId.TROGEN, wall.ladders.single().ladder.id)
    }

    @Test
    fun `a member with nothing gets an empty wall, not a grid of locks`() {
        val wall = PublicBadgeWall.from(emptyList())

        assertFalse(wall.hasAnyBadge)
        assertEquals(0, wall.earnedCount)
        assertTrue(wall.ladders.isEmpty())
        assertTrue(wall.milestones.isEmpty())
    }

    @Test
    fun `standalone milestones render in catalog order`() {
        val wall =
            PublicBadgeWall.from(
                listOf(badge("garage_created"), badge("first_event"), badge("early_member")),
            )
        assertEquals(
            listOf("first_event", "early_member", "garage_created"),
            wall.milestones.map { it.key },
        )
    }

    @Test
    fun `unknown keys are ignored so a newer catalog cannot break an old client`() {
        val wall = PublicBadgeWall.from(listOf(badge("kronjagare_brons"), badge("a_badge_from_2027")))

        assertEquals(1, wall.earnedCount)
        assertEquals(1, wall.ladders.size)
    }

    @Test
    fun `an undated award carries no date rather than a fabricated one`() {
        val wall = PublicBadgeWall.from(listOf(badge("konvojledare_brons", awardedAtMillis = null)))
        assertNull(wall.ladders.single().awardedAtMillis)
    }

    // -----------------------------------------------------------------------
    // Own wall vs public wall, side by side
    // -----------------------------------------------------------------------

    @Test
    fun `the public wall agrees with the owner's on trophies and differs on everything else`() {
        val badges = listOf(badge("vagfarare_brons"), badge("vagfarare_silver"), badge("first_event"))
        val owner =
            BadgeShowcase.from(
                badges = badges,
                counters = BadgeCounters(savedDriveDistanceMeters = 900_000.0, vehiclesInGarage = 4),
            )
        val public = PublicBadgeWall.from(badges)

        // Same trophies.
        assertEquals(owner.earnedCount, public.earnedCount)
        assertEquals(owner.totalCount, public.totalCount)
        assertEquals(owner.milestones.map { it.key }, public.milestones.map { it.key })
        val ownerVagfarare = owner.ladders.first { it.ladder.id == BadgeLadderId.VAGFARARE }
        val publicVagfarare = public.ladders.first { it.ladder.id == BadgeLadderId.VAGFARARE }
        assertEquals(ownerVagfarare.highestRung, publicVagfarare.highestRung)

        // The owner sees the climb; the public wall has no climb to see.
        assertEquals(BadgeTier.GULD, ownerVagfarare.nextRung?.tier)
        assertEquals(900_000L, ownerVagfarare.observedValue)
        assertEquals(6, owner.ladders.size)
        assertEquals(1, public.ladders.size)
    }
}
