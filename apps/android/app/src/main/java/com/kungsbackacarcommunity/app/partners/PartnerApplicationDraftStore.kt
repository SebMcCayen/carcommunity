package com.kungsbackacarcommunity.app.partners

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal data class PartnerApplicationDraft(val form: PartnerApplicationForm, val step: Int)

/** Private account-scoped text draft, excluded from backups. Logo bytes are never retained. */
internal class PartnerApplicationDraftStore(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private fun file(uid: String): File {
        require(uid.isNotBlank())
        val accountKey = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "partner-application-$accountKey.json")
    }

    fun read(uid: String): PartnerApplicationDraft? = synchronized(monitor) {
        purgeExpired()
        val source = file(uid)
        if (!source.exists()) return@synchronized null
        try {
            require(source.length() <= 64 * 1024)
            val data = JSONObject(source.readText())
            val now = nowMillis()
            val saved = data.getLong("savedAt")
            require(saved <= now + 5 * 60_000 && saved > now - TTL_MILLIS)
            PartnerApplicationDraft(PartnerApplicationForm(
                companyName = data.optString("companyName"),
                category = data.optString("category").takeIf(String::isNotBlank)?.let(PartnerCategory::fromWire),
                contactName = data.optString("contactName"), contactEmail = data.optString("contactEmail"),
                contactPhone = data.optString("contactPhone"), websiteUrl = data.optString("websiteUrl"),
                message = data.optString("message"), organizationNumber = data.optString("organizationNumber"),
                description = data.optString("description"), address = data.optString("address"), publicPhone = data.optString("publicPhone"),
                selfServiceRequested = data.optBoolean("selfServiceRequested"), billboardRequested = data.optBoolean("billboardRequested"),
                billboardPlacementRequest = data.optString("billboardPlacementRequest"),
            ), data.optInt("step").coerceIn(0, 3))
        } catch (_: Exception) { source.delete(); null }
    }

    fun save(uid: String, draft: PartnerApplicationDraft): Unit = synchronized(monitor) {
        purgeExpired()
        directory.mkdirs()
        val form = draft.form
        val data = JSONObject().apply {
            put("savedAt", nowMillis()); put("step", draft.step.coerceIn(0, 3))
            put("companyName", form.companyName); put("category", form.category?.wire.orEmpty())
            put("contactName", form.contactName); put("contactEmail", form.contactEmail)
            put("contactPhone", form.contactPhone); put("websiteUrl", form.websiteUrl)
            put("message", form.message); put("organizationNumber", form.organizationNumber)
            put("description", form.description); put("address", form.address); put("publicPhone", form.publicPhone)
            put("selfServiceRequested", form.selfServiceRequested); put("billboardRequested", form.billboardRequested)
            put("billboardPlacementRequest", form.billboardPlacementRequest)
        }.toString().toByteArray()
        require(data.size <= 64 * 1024)
        val destination = file(uid)
        val temporary = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        Unit
    }

    /** Enforce the age limit for all accounts whenever the app starts or a draft is used. */
    fun purgeExpired(): Unit = synchronized(monitor) {
        val now = nowMillis()
        directory.listFiles { file -> file.name.startsWith("partner-application-") && (file.name.endsWith(".json") || file.name.endsWith(".tmp")) }?.forEach { stored ->
            val valid = runCatching {
                require(!stored.name.endsWith(".tmp"))
                require(stored.length() <= 64 * 1024)
                val saved = JSONObject(stored.readText()).getLong("savedAt")
                saved > now - TTL_MILLIS && saved <= now + 5 * 60_000
            }.getOrDefault(false)
            if (!valid) stored.delete()
        }
        Unit
    }

    fun clear(uid: String) = synchronized(monitor) { file(uid).delete(); Unit }

    companion object {
        private val monitor = Any()
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        fun forContext(context: Context) = PartnerApplicationDraftStore(context.noBackupFilesDir)
    }
}
