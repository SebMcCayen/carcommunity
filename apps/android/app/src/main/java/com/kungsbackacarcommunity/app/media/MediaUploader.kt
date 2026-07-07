package com.kungsbackacarcommunity.app.media

/**
 * Cloud Storage upload boundary (Phase 12 media-uploads slice). Firebase-free
 * so the avatar/vehicle upload flows can be unit-tested with a fake uploader.
 *
 * Implementations upload raw bytes to an exact object path with a content-type
 * and return the path on success. The path — not a download URL — is what the
 * app persists (users/{uid}.avatarPath, vehicles/{id}.imagePath); a URL is
 * resolved lazily at render time.
 */
interface MediaUploader {
    /**
     * Uploads [bytes] to [path] with [contentType] metadata and returns [path].
     *
     * @throws Exception when the upload is rejected (rules, network, size).
     */
    suspend fun upload(path: String, bytes: ByteArray, contentType: String): String
}
