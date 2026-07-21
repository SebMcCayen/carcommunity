package com.kungsbackacarcommunity.app.garage

/**
 * Pure, JVM-testable helpers for the car-detail photo gallery.
 *
 * The gallery UI ([VehicleDetailScreen]) is written to render N photos, but the
 * backend today stores only ONE (vehicles/{id}.imagePath — a single nullable
 * Storage path; see contracts/schemas/garage.schema.json). [photoPaths]
 * therefore yields at most one entry now, and is the single seam that changes
 * the day the model gains a multi-photo array field: point it at that array and
 * the pager, thumbnails and counter all follow with no further UI change.
 *
 * Keeping the derivation here (not inline in the composable) makes the
 * empty/blank/single/N handling unit-testable without a device — the gallery
 * index clamping in particular is easy to get subtly wrong when the photo list
 * shrinks (e.g. a future remove-photo) underneath a remembered pager position.
 */
object VehicleGallery {
    /**
     * The vehicle's photo Storage paths, in display order, blanks dropped.
     *
     * Today this is `listOfNotNull(imagePath)` because the data model holds a
     * single photo; it is deliberately shaped to return N so the gallery is
     * already correct once a photoPaths array exists on the wire.
     */
    fun photoPaths(vehicle: Vehicle): List<String> =
        listOfNotNull(vehicle.imagePath?.takeIf { it.isNotBlank() })

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
}
