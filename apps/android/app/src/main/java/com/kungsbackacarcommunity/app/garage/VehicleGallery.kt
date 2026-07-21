package com.kungsbackacarcommunity.app.garage

/**
 * Pure, JVM-testable helpers for the car-detail photo gallery.
 *
 * The gallery renders N photos off [Vehicle.photoPaths] (cover first). Legacy
 * documents that predate that array carry only [Vehicle.imagePath]; [photoPaths]
 * falls back to `listOfNotNull(imagePath)` for them, so a single-photo car still
 * shows its photo. The cover is always the first entry.
 *
 * Keeping the derivation here (not inline in the composable) makes the
 * empty/blank/single/N handling unit-testable without a device — the gallery
 * index clamping in particular is easy to get subtly wrong when the photo list
 * shrinks (a remove-photo) underneath a remembered pager position.
 */
object VehicleGallery {
    /**
     * The vehicle's photo Storage paths, in display order (cover first), blanks
     * dropped. Uses [Vehicle.photoPaths] when present; for a legacy document
     * that only has [Vehicle.imagePath] it falls back to that single photo.
     */
    fun photoPaths(vehicle: Vehicle): List<String> {
        val explicit = vehicle.photoPaths.filter { it.isNotBlank() }
        return explicit.ifEmpty {
            listOfNotNull(vehicle.imagePath?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * A safe current-photo index for a gallery of [count] photos.
     *
     * Clamps into `0..count-1`, and collapses to 0 for an empty gallery so a
     * remembered pager position can never point past the end after the photo
     * list shrinks (or is briefly empty while a path resolves).
     */
    fun clampIndex(index: Int, count: Int): Int =
        if (count <= 0) 0 else index.coerceIn(0, count - 1)

    /** True when the gallery should show its thumbnail strip + "x / n" counter. */
    fun hasMultiple(count: Int): Boolean = count > 1

    /** True when [path] is already the cover (first) photo of [paths]. */
    fun isCover(paths: List<String>, path: String): Boolean = paths.firstOrNull() == path

    /**
     * The order to send to garage.reorderVehiclePhotos to make [path] the cover:
     * [path] first, every other photo kept in its current relative order. Always
     * a permutation of [paths] (the callable rejects anything else). Returns
     * [paths] unchanged when [path] is not part of the gallery.
     */
    fun moveToCover(paths: List<String>, path: String): List<String> {
        if (!paths.contains(path)) return paths
        return listOf(path) + paths.filter { it != path }
    }
}
