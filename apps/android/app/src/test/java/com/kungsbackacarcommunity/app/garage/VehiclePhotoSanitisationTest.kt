package com.kungsbackacarcommunity.app.garage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the invariant a security audit put here: a vehicle photo must never
 * reach Storage without going through
 * `ImageCompressor.compressForPublicUpload`, which is what strips the owner's
 * GPS/EXIF. Vehicle photos are public to every member, so an unsanitised one
 * publishes the coordinates of wherever the car was photographed — usually home.
 *
 * Adding a CROP step is exactly the change that historically breaks this. The
 * obvious shapes all bypass the sanitiser:
 *  - a crop library that hands back a `Uri` to a file it wrote (uCrop, CanHub) —
 *    those files are re-encoded from the ORIGINAL and commonly copy the source
 *    EXIF forward on purpose, and the caller then uploads that Uri directly;
 *  - an in-app crop that returns the cropped `Bitmap`/`ByteArray` and uploads
 *    it, because it "already re-encoded, so it must be clean".
 *
 * The design that makes both impossible: the crop UI produces a
 * `NormalizedCropRect` — a WINDOW, not pixels — and the cut is made inside
 * `compressForPublicUpload` alongside the stripping. This test pins that design
 * against the source, because it is a structural property (which call sites
 * exist) rather than a behavioural one, and because the crop UI itself can only
 * be exercised on a device.
 *
 * The pixel-level proof that the cropped OUTPUT carries no GPS lives in the
 * instrumented `media.ImageCompressorTest` — real Bitmap/ExifInterface are
 * stubbed to throw in JVM tests.
 */
class VehiclePhotoSanitisationTest {

    // The shared gesture editor every image upload (avatar + vehicle) routes
    // through. It replaced the old garage/VehiclePhotoCropScreen and inherits the
    // same guarantee: it produces a crop WINDOW (+ a rotation angle), never bytes.
    private val cropScreen = "media/ImageEditScreen.kt"
    private val route = "garage/GarageRoute.kt"

    /** The comment stripper must not be able to hide a real violation. */
    @Test
    fun commentStripperKeepsCode() {
        val stripped =
            stripComments(
                """
                /** Must not call compressForPublicUpload here. */
                val a = ByteArrayOutputStream() // encodes
                """.trimIndent(),
            )
        assertTrue("code must survive", stripped.contains("ByteArrayOutputStream"))
        assertTrue("KDoc must be removed", !stripped.contains("compressForPublicUpload"))
        assertTrue("line comment must be removed", !stripped.contains("encodes"))
    }

    /**
     * The crop screen must have no way to produce an uploadable artefact: no
     * encoding, no file writing, no uploader. If it cannot make bytes, it cannot
     * hand unsanitised bytes to Storage.
     */
    @Test
    fun cropScreenCannotProduceOrUploadBytes() {
        val source = readSource(cropScreen)
        val forbidden =
            listOf(
                "CompressFormat" to "encodes an image",
                "ByteArrayOutputStream" to "encodes an image",
                "MediaUploader" to "can upload",
                "ImageUploadCoordinator" to "can upload",
                "FileOutputStream" to "writes an image file",
                "createTempFile" to "writes an image file",
                "compressForPublicUpload" to "should not sanitise here; the route does",
            )
        forbidden.forEach { (needle, why) ->
            assertTrue(
                "$cropScreen must not reference `$needle` — it $why, and the crop " +
                    "step must hand back a NormalizedCropRect (a window), never pixels. " +
                    "Cropped bytes may only be produced inside " +
                    "ImageCompressor.compressForPublicUpload so EXIF/GPS stripping " +
                    "cannot be skipped.",
                !source.contains(needle),
            )
        }
    }

    /**
     * Teeth for the above: the confirm callback's parameter type is the whole
     * guarantee. If it ever becomes a Bitmap/ByteArray, an unsanitised upload
     * path becomes expressible and this test must fail.
     */
    @Test
    fun cropConfirmHandsBackAWindowNotAnImage() {
        val source = readSource(cropScreen)
        assertTrue(
            "$cropScreen must hand back a `NormalizedCropRect` window (alongside the " +
                "free-rotation angle), so its onConfirm ends `crop: NormalizedCropRect) " +
                "-> Unit`. Any image-bearing type here (Bitmap, ByteArray, Uri, " +
                "PickedImage) would let the editor emit pixels that skip sanitisation.",
            source.contains("crop: NormalizedCropRect) -> Unit"),
        )
        listOf("Bitmap", "ByteArray", "Uri", "PickedImage").forEach { type ->
            assertTrue(
                "$cropScreen must not declare an `onConfirm` emitting $type.",
                !source.contains("onConfirm: ($type)") &&
                    !source.contains(": $type) -> Unit"),
            )
        }
    }

    /**
     * The route is where a pick becomes an upload. Every upload must be fed by
     * the sanitiser, and the crop must travel to it as a parameter — not as a
     * pre-cropped image.
     */
    @Test
    fun routeUploadsOnlySanitiserOutput() {
        val source = readSource(route)

        assertTrue(
            "$route must pass the user's crop INTO the sanitiser " +
                "(compressForPublicUpload(..., crop = ...)), so the cut and the " +
                "EXIF strip happen in one place.",
            Regex("""compressForPublicUpload\(.*?crop = """, RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(source),
        )

        // The two upload call sites are the edit-mode upload (at crop confirm)
        // and the add-mode upload (once Save mints the vehicle id). Both take a
        // value that came out of compressForPublicUpload. A third would be a new,
        // unreviewed path to Storage.
        val uploadCalls = Regex("""photoCoordinator\.upload\(\s*(\w+)""").findAll(source).toList()
        assertEquals(
            "$route should have exactly two photoCoordinator.upload call sites " +
                "(edit-mode and add-mode). Found: " +
                uploadCalls.joinToString { it.groupValues[1] },
            2,
            uploadCalls.size,
        )
        uploadCalls.forEach { match ->
            val uploaded = match.groupValues[1]
            assertTrue(
                "$route uploads `$uploaded`, which is not a sanitiser result. " +
                    "Only `sanitized` (straight out of compressForPublicUpload) or " +
                    "`photo` (the already-sanitised pendingPhoto) may be uploaded — " +
                    "uploading the raw pick or a cropped preview would publish the " +
                    "owner's GPS coordinates.",
                uploaded in setOf("sanitized", "photo"),
            )
        }

        // The raw pick and the display-only preview must never be handed to an
        // upload, directly or as pendingPhoto (which IS uploaded later).
        listOf("cropCandidate", "cropPreview", "preview").forEach { raw ->
            assertTrue(
                "$route must not upload or stash `$raw` — it is unsanitised.",
                !source.contains("upload($raw") && !source.contains("pendingPhoto = $raw"),
            )
        }
    }

    /**
     * Strips `//` and block comments so the assertions read CODE, not prose.
     * Both files document the bypass they are avoiding by naming it, and a KDoc
     * explaining why the crop screen must not call `compressForPublicUpload`
     * must not itself trip the check.
     *
     * Deliberately naive: it would also blank a `//` inside a string literal.
     * Neither file contains one, and a false NEGATIVE is not possible — worst
     * case it hides code from the scan, which the reviewed diff would catch.
     */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /**
     * Walks up from the test's working directory to the `app` module, so the
     * test does not depend on Gradle's choice of working directory for the JVM
     * test task (it is the module dir today, but that is not contractual).
     */
    private fun readSource(relativePath: String): String = stripComments(readRaw(relativePath))

    private fun readRaw(relativePath: String): String {
        val suffix = "src/main/java/com/kungsbackacarcommunity/app/$relativePath"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, suffix).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, "app/$suffix").takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate app/$suffix from " + System.getProperty("user.dir"),
        )
    }
}
