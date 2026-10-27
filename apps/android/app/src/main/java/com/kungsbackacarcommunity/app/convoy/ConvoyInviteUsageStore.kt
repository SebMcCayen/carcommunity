package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

interface ConvoyInviteUsageStore {
    fun counts(uid: String): Map<String, Int>
    fun record(uid: String, invitedUids: List<String>)
}

/**
 * Device-local ordering hint, never an authorization source or analytics event.
 * Stores only uid/count pairs: no names, locations, convoy ids or timestamps.
 * At most four accounts and 200 friends/account; least-recently-updated accounts
 * and lowest counts are evicted. App backup is disabled in AndroidManifest.xml.
 */
class LocalConvoyInviteUsageStore(private val preferences: SharedPreferences) : ConvoyInviteUsageStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("convoy_invite_usage", Context.MODE_PRIVATE),
    )

    override fun counts(uid: String): Map<String, Int> = synchronized(lock) {
        if (!validUid(uid)) return@synchronized emptyMap()
        readAccounts()[uid].orEmpty()
    }

    override fun record(uid: String, invitedUids: List<String>) = synchronized(lock) {
        if (!validUid(uid)) return@synchronized
        val invited = invitedUids.filter { validUid(it) && it != uid }.distinct()
            .take(MAX_CREATE_CONVOY_INVITEES)
        if (invited.isEmpty()) return@synchronized
        val accounts = readAccounts()
        val counts = accounts.remove(uid).orEmpty().toMutableMap()
        invited.forEach { friend -> counts[friend] = ((counts[friend] ?: 0) + 1).coerceAtMost(MAX_COUNT) }
        accounts[uid] = counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        ).take(MAX_FRIENDS).associate { it.key to it.value }
        val json = JSONArray()
        accounts.entries.toList().takeLast(MAX_ACCOUNTS).forEach { (owner, usage) ->
            json.put(JSONObject().put("uid", owner).put("counts", JSONObject(usage)))
        }
        // Also bound the encoded size when unusual uid characters need escaping.
        while (json.toString().length > MAX_ENCODED_LENGTH && json.length() > 0) json.remove(0)
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    private fun readAccounts(): LinkedHashMap<String, Map<String, Int>> {
        val accounts = linkedMapOf<String, Map<String, Int>>()
        try {
            val encoded = preferences.getString(KEY, null) ?: return accounts
            if (encoded.length > MAX_ENCODED_LENGTH) return accounts
            val json = JSONArray(encoded)
            for (i in 0 until minOf(json.length(), MAX_ACCOUNTS)) {
                val account = json.optJSONObject(i) ?: continue
                val uid = account.optString("uid")
                if (!validUid(uid)) continue
                val counts = account.optJSONObject("counts") ?: continue
                accounts[uid] = counts.keys().asSequence().take(MAX_FRIENDS)
                    .filter { validUid(it) && it != uid }
                    .mapNotNull { friend ->
                        counts.optInt(friend, 0).takeIf { it > 0 }
                            ?.let { friend to it.coerceAtMost(MAX_COUNT) }
                    }.toMap()
            }
        } catch (_: Exception) {
            // Corrupt/old local hints fall back to name ordering, never a failed create.
        }
        return accounts
    }

    private companion object {
        val lock = Any()
        const val KEY = "accounts_v1"
        const val MAX_ACCOUNTS = 4
        const val MAX_FRIENDS = 200
        const val MAX_COUNT = 1_000_000
        const val MAX_ENCODED_LENGTH = 150_000
        fun validUid(uid: String) = uid.isNotBlank() && uid.length <= 128
    }
}
