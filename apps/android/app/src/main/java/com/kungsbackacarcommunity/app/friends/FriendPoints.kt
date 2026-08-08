package com.kungsbackacarcommunity.app.friends

import kotlin.math.abs

/**
 * Reads members' PUBLIC Crown Points balances — the same denormalized
 * `pointsLedger/{uid}.balance` the member profile surfaces as its headline
 * number (firestore.rules: `get`-by-id for any authenticated user; the `entries`
 * subcollection behind it stays owner-only, so only the balance is read).
 *
 * This is deliberately NOT `crownHuntUserStats/{uid}`: that document is
 * owner-and-admin only (a member cannot read another member's rich stats) and it
 * does not carry a points balance at all — the wallet balance lives on
 * `pointsLedger`. Using the same source as the profile also means a friend's
 * Crown Points read identically wherever they appear.
 *
 * Firebase-free interface so [FriendsCoordinator] stays unit-testable with a
 * fake. The one implementation ([FirebaseFriendPointsRepository]) is direct,
 * rules-gated `get()` reads.
 */
interface FriendPointsRepository {
    /**
     * Best-effort per-uid balance lookup for the friends list. A uid with no
     * wallet — or one whose read failed — is simply ABSENT from the returned map
     * (the screen renders that as 0), so a points read can never fail or block
     * the friends list itself. Reads are by document id (the rule grants `get`,
     * never `list`), one per uid.
     */
    suspend fun balancesFor(uids: List<String>): Map<String, Long>
}

/**
 * Pure, locale-independent formatting of a Crown Points balance for the compact
 * chip beside a friend's name.
 */
object FriendPointsFormat {
    /**
     * Groups thousands with a space ("1 240", "12 000") — the Swedish
     * digit-grouping convention, which reads naturally in both locales. The chip
     * renders on a single line, so the ordinary space is fine. A negative balance
     * never occurs (the ledger floors at 0), but the sign is preserved if one
     * ever does.
     */
    fun grouped(balance: Long): String {
        val negative = balance < 0
        val digits = abs(balance).toString()
        val firstGroup = digits.length % 3
        val sb = StringBuilder()
        for (i in digits.indices) {
            if (i != 0 && (i - firstGroup) % 3 == 0) sb.append(' ')
            sb.append(digits[i])
        }
        return if (negative) "-$sb" else sb.toString()
    }
}
