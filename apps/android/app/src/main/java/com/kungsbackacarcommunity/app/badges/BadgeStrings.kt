package com.kungsbackacarcommunity.app.badges

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/**
 * Localized badge-name lookup (Phase 12 slice 14). Returns the res for a known
 * badge key, or null for an unknown key (the screen falls back to the
 * denormalized name from the document).
 */
@StringRes
fun badgeNameRes(key: String): Int? =
    when (key) {
        "first_event" -> R.string.badges_badgeNames_first_event
        "five_events" -> R.string.badges_badgeNames_five_events
        "helpful_member" -> R.string.badges_badgeNames_helpful_member
        "early_member" -> R.string.badges_badgeNames_early_member
        "garage_created" -> R.string.badges_badgeNames_garage_created
        else -> null
    }

/**
 * Ladder name (Kronjägare, Vägfarare, …). These are Swedish PRODUCT names and
 * stay Swedish in every locale — the English contract carries the same words on
 * purpose, so a member always recognises the badge they were awarded.
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
    }

@StringRes
fun tierNameRes(tier: BadgeTier): Int =
    when (tier) {
        BadgeTier.BRONS -> R.string.badgeShowcase_tierNames_brons
        BadgeTier.SILVER -> R.string.badgeShowcase_tierNames_silver
        BadgeTier.GULD -> R.string.badgeShowcase_tierNames_guld
        BadgeTier.PLATINA -> R.string.badgeShowcase_tierNames_platina
    }
