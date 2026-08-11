package com.kungsbackacarcommunity.app.garage

import android.content.Context
import org.json.JSONObject

/**
 * A not-yet-saved NEW-CAR form, held on disk so its typed text and catalogue
 * selections survive the form being closed — by an accidental pull-dismiss, a
 * tab switch, or a process death — and can be offered back the next time the
 * add form is opened (issue #796).
 *
 * ONLY the add form drafts: an edit already has a saved vehicle to fall back to,
 * and drafting arbitrary edits is a separate, larger idea (deliberately out of
 * scope here). The picked PHOTO is never part of the draft — its bytes are far
 * too large for this store and are dropped on any teardown exactly like
 * `GarageRoute`'s `pendingPhoto` (see its KDoc).
 */
data class VehicleFormDraft(
    val form: VehicleForm,
    val savedAtMillis: Long,
)

/**
 * Device-local persistence for the add-car [VehicleFormDraft].
 *
 * SharedPreferences, no Firebase — mirroring
 * [com.kungsbackacarcommunity.app.shell.CompassModePreferenceStore],
 * [com.kungsbackacarcommunity.app.update.AppUpdateDismissalStore] and
 * [com.kungsbackacarcommunity.app.navigation.NavResumeStore]. A half-filled car
 * on this phone is not account state worth syncing, it must survive a cold start
 * (so it lives on disk, not just in `rememberSaveable`), and a Firestore write
 * per keystroke would be absurd.
 *
 * The whole draft is a single JSON blob under one key, written and read
 * together: a partially written record decodes to null (treated as no draft),
 * so a crash mid-write can only ever cost the restore prompt, never resurrect a
 * corrupt car. The pure [encode]/[decode]/[isFresh]/[hasUserContent] half lives
 * in the companion so the (de)serialisation and the 24h TTL are JVM-unit-testable
 * without a `Context` — the same split as
 * [com.kungsbackacarcommunity.app.navigation.PrefsSavedPlacesStore].
 */
class VehicleFormDraftStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted draft, or null when there is none / it is unreadable. */
    fun read(): VehicleFormDraft? = decode(prefs.getString(KEY_JSON, null))

    /**
     * The persisted draft IF it is still within [TTL_MILLIS] of [nowMillis],
     * else null. A stale (or unreadable) draft is CLEARED as a side effect, so a
     * day-old car never lingers to be offered again.
     */
    fun readFresh(nowMillis: Long): VehicleFormDraft? {
        val draft = read()
        if (draft == null || !isFresh(draft.savedAtMillis, nowMillis)) {
            clear()
            return null
        }
        return draft
    }

    /** Records [form] as the in-progress add-car draft, stamped [nowMillis]. */
    fun write(form: VehicleForm, nowMillis: Long) {
        prefs.edit().putString(KEY_JSON, encode(form, nowMillis)).apply()
    }

    /** Forgets any draft — called on a successful save and on an explicit discard. */
    fun clear() {
        prefs.edit().remove(KEY_JSON).apply()
    }

    companion object {
        /**
         * How long a draft is offered for. A day: long enough to survive an
         * overnight interruption, short enough that a car you abandoned last week
         * is not dredged back up as if you meant to keep it.
         */
        const val TTL_MILLIS: Long = 24L * 60 * 60 * 1000

        private const val PREFS_NAME = "vehicle_form_draft"
        private const val KEY_JSON = "draft"

        private const val FIELD_SAVED_AT = "savedAt"
        private const val FIELD_MAKE_ID = "makeId"
        private const val FIELD_MODEL_ID = "modelId"
        private const val FIELD_MODEL_YEAR = "modelYear"
        private const val FIELD_POWERTRAIN = "powertrain"
        private const val FIELD_ENGINE = "engine"
        private const val FIELD_MODIFICATIONS = "modifications"
        private const val FIELD_PLATE = "plate"

        /**
         * Whether a draft stamped [savedAtMillis] is still fresh at [nowMillis].
         *
         * A NEGATIVE age (the clock moved backwards, or a draft stamped in the
         * future by a wrong device clock) is treated as stale rather than
         * "infinitely fresh" — the restore prompt should fail closed.
         */
        fun isFresh(savedAtMillis: Long, nowMillis: Long): Boolean {
            val age = nowMillis - savedAtMillis
            return age in 0..TTL_MILLIS
        }

        /**
         * Whether [form] holds anything a user actually entered — the "dirty"
         * test behind both the discard confirm and whether a draft is worth
         * writing. The read-only [VehicleForm.legacyMake] / [VehicleForm.legacyModel]
         * carriers are deliberately ignored: they are never present on the ADD
         * form and are not user input even when they are.
         */
        fun hasUserContent(form: VehicleForm): Boolean =
            form.makeId != null ||
                form.modelId != null ||
                form.modelYear != null ||
                form.powertrain != null ||
                form.engineDescription.isNotBlank() ||
                form.modifications.isNotBlank() ||
                form.registrationPlate.isNotBlank()

        /**
         * Whether dismissing the form should ask "Discard new car?" first. Only a
         * NEW car with actual input is worth confirming: an edit has a saved
         * vehicle to fall back to, and an untouched add form has nothing to lose,
         * so both close straight away.
         */
        fun shouldConfirmDismiss(isAddMode: Boolean, form: VehicleForm): Boolean =
            isAddMode && hasUserContent(form)

        /** Serialises [form] + [savedAtMillis] to the stored JSON string. */
        fun encode(form: VehicleForm, savedAtMillis: Long): String {
            val json = JSONObject()
            json.put(FIELD_SAVED_AT, savedAtMillis)
            // Nulls are written as JSONObject.NULL so decode can tell "unset" from
            // "empty string"; text fields always round-trip as themselves.
            json.put(FIELD_MAKE_ID, form.makeId ?: JSONObject.NULL)
            json.put(FIELD_MODEL_ID, form.modelId ?: JSONObject.NULL)
            json.put(FIELD_MODEL_YEAR, form.modelYear ?: JSONObject.NULL)
            json.put(FIELD_POWERTRAIN, form.powertrain?.wire ?: JSONObject.NULL)
            json.put(FIELD_ENGINE, form.engineDescription)
            json.put(FIELD_MODIFICATIONS, form.modifications)
            json.put(FIELD_PLATE, form.registrationPlate)
            return json.toString()
        }

        /**
         * Parses a stored draft, or null when [raw] is absent, not JSON, or
         * missing its timestamp. Every field is read defensively: an unknown
         * powertrain wire (a value this build has since retired) or a
         * non-numeric year decodes to "unset" rather than throwing, so a draft
         * written by an older build can never crash the add form.
         */
        fun decode(raw: String?): VehicleFormDraft? {
            if (raw.isNullOrBlank()) return null
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            if (!json.has(FIELD_SAVED_AT)) return null
            val savedAt = json.optLong(FIELD_SAVED_AT, Long.MIN_VALUE)
            if (savedAt == Long.MIN_VALUE) return null

            val form = VehicleForm(
                makeId = json.optNullableString(FIELD_MAKE_ID),
                modelId = json.optNullableString(FIELD_MODEL_ID),
                // Absent or JSONObject.NULL -> unset; any non-int stored value
                // (an older/corrupt payload) also decodes to unset rather than a
                // spurious 0 that the year picker would show as selected.
                modelYear = if (!json.has(FIELD_MODEL_YEAR) || json.isNull(FIELD_MODEL_YEAR)) {
                    null
                } else {
                    (json.get(FIELD_MODEL_YEAR) as? Int) ?: (json.get(FIELD_MODEL_YEAR) as? Number)?.toInt()
                },
                powertrain = VehiclePowertrain.fromWire(json.optNullableString(FIELD_POWERTRAIN)),
                engineDescription = json.optString(FIELD_ENGINE, ""),
                modifications = json.optString(FIELD_MODIFICATIONS, ""),
                registrationPlate = json.optString(FIELD_PLATE, ""),
            )
            return VehicleFormDraft(form = form, savedAtMillis = savedAt)
        }

        /** `optString` treats JSONObject.NULL as the string "null"; this keeps it null. */
        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key, "").ifEmpty { null }
    }
}
