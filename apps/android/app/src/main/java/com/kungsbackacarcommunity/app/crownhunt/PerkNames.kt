package com.kungsbackacarcommunity.app.crownhunt

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import com.kungsbackacarcommunity.app.R

/**
 * The BILINGUAL display name for a Kronjakt perk.
 *
 * Every perk has BOTH a Swedish and an English name, wired two ways so the shop
 * always renders in the member's chosen app language:
 *  - the CATALOG mirror carries both `name` (sv) and `nameEn` (en) — see
 *    functions `crownHunt/perks-core.ts` — reaching the client as
 *    [PerkCatalogEntry.name] / [PerkCatalogEntry.nameEn];
 *  - the CONTRACTS localization (`contracts/localization/{sv,en}.json`, mirrored
 *    to the `res/values` and `res/values-en` strings) carries a per-perk
 *    `crownHunt_perkName…` string, which — being a resource — the framework
 *    resolves in the SAME language as the rest of the UI, offline and instantly.
 *
 * Resolution prefers the localized string resource for the three known perks
 * (the authoritative, offline display); an unknown/future perk falls back to the
 * catalog's own bilingual pair, picking [nameEn] when the app is showing English
 * and [nameSv] otherwise.
 */
@Composable
fun perkDisplayName(perkId: String, nameSv: String, nameEn: String = ""): String {
    perkNameResOrNull(perkId)?.let { return stringResource(it) }
    val english = Locale.current.language.equals("en", ignoreCase = true)
    return if (english && nameEn.isNotBlank()) nameEn else nameSv
}

/** The per-perk localized name resource for the known perks, or null. */
@StringRes
private fun perkNameResOrNull(perkId: String): Int? =
    when (perkId) {
        "spike_strip" -> R.string.crownHunt_perkNameSpikeStrip
        "shield" -> R.string.crownHunt_perkNameShield
        "boost" -> R.string.crownHunt_perkNameBoost
        else -> null
    }
