package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.shell.MapCrownMarker

/** Presentation only: the controller retains shared spawns and the uid-scoped collected cache. */
object CrownSpawnMarkers {
    fun markers(
        spawns: List<CrownSpawn>,
        visible: Boolean,
        collectedIds: Set<String>,
        inRangeIds: Set<String>? = null,
        zoomedOut: Boolean = false,
        glyphRes: (CrownRarity) -> Int,
    ): List<MapCrownMarker> {
        if (!visible) return emptyList()
        return CrownZoomThinning.visibleSpawns(spawns, zoomedOut).mapNotNull { spawn ->
            // Local confirmed claims and server collector sync feed the same durable set.
            // Omit only this member's collected crowns; shared crowns remain live for others.
            if (spawn.id in collectedIds) return@mapNotNull null
            val inRange = inRangeIds?.contains(spawn.id) == true
            MapCrownMarker(
                id = spawn.id,
                longitude = spawn.longitude,
                latitude = spawn.latitude,
                discColorArgb = CrownMarkerStyle.discColorArgb(spawn.rarity, inRange),
                iconRes = glyphRes(spawn.rarity),
                glyphColorArgb = CrownMarkerStyle.glyphColorArgb(spawn.rarity, inRange),
                // Rank is carried by metadata; compact map tiles have no halo.
                glowColorArgb = null,
                rarity = spawn.rarity,
                rewardPoints = spawn.rewardPoints,
            )
        }
    }
}
