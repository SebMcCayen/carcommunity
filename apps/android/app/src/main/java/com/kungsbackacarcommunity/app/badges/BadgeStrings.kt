package com.kungsbackacarcommunity.app.badges

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/**
 * Localized badge-name lookup (Phase 12 slice 14). Returns the res for a known
 * badge key, or null for an unknown key (the screen falls back to the
 * denormalized name from the document, which is always Swedish).
 *
 * Every key in the backend catalog (functions/src/badges/badge-core.ts) must
 * appear here, otherwise an English-locale member sees the Swedish fallback:
 * the eight milestone keys plus the 31 tiered ladder rungs. The strings
 * themselves come from contracts/localization/{sv,en}.json via
 * apps/android/scripts/generate-strings.mjs — never hand-edited.
 */
@StringRes
fun badgeNameRes(key: String): Int? =
    when (key) {
        "first_event" -> R.string.badges_badgeNames_first_event
        "five_events" -> R.string.badges_badgeNames_five_events
        "helpful_member" -> R.string.badges_badgeNames_helpful_member
        "early_member" -> R.string.badges_badgeNames_early_member
        "garage_created" -> R.string.badges_badgeNames_garage_created
        "early_tester" -> R.string.badges_badgeNames_early_tester
        "sasong_guld" -> R.string.badges_badgeNames_sasong_guld
        "sasong_silver" -> R.string.badges_badgeNames_sasong_silver
        "sasong_brons" -> R.string.badges_badgeNames_sasong_brons
        "kronjagare_brons" -> R.string.badges_badgeNames_kronjagare_brons
        "kronjagare_silver" -> R.string.badges_badgeNames_kronjagare_silver
        "kronjagare_guld" -> R.string.badges_badgeNames_kronjagare_guld
        "kronjagare_platina" -> R.string.badges_badgeNames_kronjagare_platina
        "vagfarare_brons" -> R.string.badges_badgeNames_vagfarare_brons
        "vagfarare_silver" -> R.string.badges_badgeNames_vagfarare_silver
        "vagfarare_guld" -> R.string.badges_badgeNames_vagfarare_guld
        "vagfarare_platina" -> R.string.badges_badgeNames_vagfarare_platina
        "traffrav_brons" -> R.string.badges_badgeNames_traffrav_brons
        "traffrav_silver" -> R.string.badges_badgeNames_traffrav_silver
        "traffrav_guld" -> R.string.badges_badgeNames_traffrav_guld
        "traffrav_platina" -> R.string.badges_badgeNames_traffrav_platina
        "trogen_brons" -> R.string.badges_badgeNames_trogen_brons
        "trogen_silver" -> R.string.badges_badgeNames_trogen_silver
        "trogen_guld" -> R.string.badges_badgeNames_trogen_guld
        "konvojledare_brons" -> R.string.badges_badgeNames_konvojledare_brons
        "konvojledare_silver" -> R.string.badges_badgeNames_konvojledare_silver
        "konvojledare_guld" -> R.string.badges_badgeNames_konvojledare_guld
        "konvojledare_platina" -> R.string.badges_badgeNames_konvojledare_platina
        "samlare_brons" -> R.string.badges_badgeNames_samlare_brons
        "samlare_silver" -> R.string.badges_badgeNames_samlare_silver
        "samlare_guld" -> R.string.badges_badgeNames_samlare_guld
        "samlare_platina" -> R.string.badges_badgeNames_samlare_platina
        "sasongsmastare_brons" -> R.string.badges_badgeNames_sasongsmastare_brons
        "sasongsmastare_silver" -> R.string.badges_badgeNames_sasongsmastare_silver
        "sasongsmastare_guld" -> R.string.badges_badgeNames_sasongsmastare_guld
        "sasongsmastare_platina" -> R.string.badges_badgeNames_sasongsmastare_platina
        "vinkare_brons" -> R.string.badges_badgeNames_vinkare_brons
        "vinkare_silver" -> R.string.badges_badgeNames_vinkare_silver
        "vinkare_guld" -> R.string.badges_badgeNames_vinkare_guld
        "vinkare_platina" -> R.string.badges_badgeNames_vinkare_platina
        else -> null
    }

/**
 * Ladder name (Crown Hunter, Wayfarer, …). Localized per locale: the English
 * contract now carries English award titles (Seb, 2026-08 — no award should read
 * Swedish in English), matching the English names in `badges.badgeNames`; Swedish
 * keeps the original product names (Kronjägare, Vägfarare, …).
 */
@StringRes
fun ladderNameRes(id: BadgeLadderId): Int =
    when (id) {
        BadgeLadderId.KRONJAGARE -> R.string.badgeShowcase_ladderNames_kronjagare
        BadgeLadderId.VAGFARARE -> R.string.badgeShowcase_ladderNames_vagfarare
        BadgeLadderId.TRAFFRAV -> R.string.badgeShowcase_ladderNames_traffrav
        BadgeLadderId.TROGEN -> R.string.badgeShowcase_ladderNames_trogen
        BadgeLadderId.KONVOJLEDARE -> R.string.badgeShowcase_ladderNames_konvojledare
        BadgeLadderId.SAMLARE -> R.string.badgeShowcase_ladderNames_samlare
        BadgeLadderId.SASONGSMASTARE -> R.string.badgeShowcase_ladderNames_sasongsmastare
        BadgeLadderId.VINKARE -> R.string.badgeShowcase_ladderNames_vinkare
    }

/** One-line "what this ladder measures" caption. */
@StringRes
fun ladderTaglineRes(id: BadgeLadderId): Int =
    when (id) {
        BadgeLadderId.KRONJAGARE -> R.string.badgeShowcase_ladderTaglines_kronjagare
        BadgeLadderId.VAGFARARE -> R.string.badgeShowcase_ladderTaglines_vagfarare
        BadgeLadderId.TRAFFRAV -> R.string.badgeShowcase_ladderTaglines_traffrav
        BadgeLadderId.TROGEN -> R.string.badgeShowcase_ladderTaglines_trogen
        BadgeLadderId.KONVOJLEDARE -> R.string.badgeShowcase_ladderTaglines_konvojledare
        BadgeLadderId.SAMLARE -> R.string.badgeShowcase_ladderTaglines_samlare
        BadgeLadderId.SASONGSMASTARE -> R.string.badgeShowcase_ladderTaglines_sasongsmastare
        BadgeLadderId.VINKARE -> R.string.badgeShowcase_ladderTaglines_vinkare
    }

/** Requirement sentence with a single `%1$s` threshold placeholder. */
@StringRes
fun ladderRequirementRes(id: BadgeLadderId): Int =
    when (id) {
        BadgeLadderId.KRONJAGARE -> R.string.badgeShowcase_ladderRequirements_kronjagare
        BadgeLadderId.VAGFARARE -> R.string.badgeShowcase_ladderRequirements_vagfarare
        BadgeLadderId.TRAFFRAV -> R.string.badgeShowcase_ladderRequirements_traffrav
        BadgeLadderId.TROGEN -> R.string.badgeShowcase_ladderRequirements_trogen
        BadgeLadderId.KONVOJLEDARE -> R.string.badgeShowcase_ladderRequirements_konvojledare
        BadgeLadderId.SAMLARE -> R.string.badgeShowcase_ladderRequirements_samlare
        BadgeLadderId.SASONGSMASTARE -> R.string.badgeShowcase_ladderRequirements_sasongsmastare
        BadgeLadderId.VINKARE -> R.string.badgeShowcase_ladderRequirements_vinkare
    }

@StringRes
fun tierNameRes(tier: BadgeTier): Int =
    when (tier) {
        BadgeTier.BRONS -> R.string.badgeShowcase_tierNames_brons
        BadgeTier.SILVER -> R.string.badgeShowcase_tierNames_silver
        BadgeTier.GULD -> R.string.badgeShowcase_tierNames_guld
        BadgeTier.PLATINA -> R.string.badgeShowcase_tierNames_platina
    }
