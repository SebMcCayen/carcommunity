package com.kungsbackacarcommunity.app.update

/**
 * Reads the server-held app version record. A Firebase-free boundary so the
 * shell wiring and the policy can be exercised against a fake.
 */
interface AppVersionConfigRepository {
    /** The current config, or null when the document is absent or malformed. */
    suspend fun fetch(): AppVersionConfig?
}
