package com.kungsbackacarcommunity.app.notifications

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chattime.ChatDateContext
import com.kungsbackacarcommunity.app.chattime.ChatDateFormat
import com.kungsbackacarcommunity.app.chattime.rememberChatDateContext
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.messageRes
import com.kungsbackacarcommunity.app.shell.AeroLazyPage
import com.kungsbackacarcommunity.app.shell.AeroPageTitle
import com.kungsbackacarcommunity.app.shell.aeroLazyContentPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-app notification inbox (Phase 12 slice 21). Stateless: renders [state],
 * reports a tap on an unread item (→ mark read), mark-all-read, and the two
 * delete actions. Uses a LazyColumn so only visible rows compose (the inbox is
 * durable and can hold many items).
 *
 * DELETING. A row is swiped away RIGHT-TO-LEFT ([SwipeToDismissBoxValue.EndToStart])
 * and no other way: one direction rather than two, so a horizontal wobble
 * during a vertical scroll has only half the chance of arming a delete, and
 * that direction specifically because a left-to-right drag is the system back
 * gesture's territory. The swipe must also cross HALF the row's width before it
 * counts, which is deliberate — a delete here is not undoable, so it should
 * take a committed gesture rather than a flick.
 *
 * A swipe is a pointer gesture and therefore invisible to a screen reader, so
 * every row ALSO carries a semantics custom action ("Delete notification").
 * TalkBack surfaces it in the actions menu, which makes deleting reachable
 * without any dragging at all; it calls exactly the same [onDeleteNotification]
 * the swipe does.
 *
 * "Delete all" is deliberately small, quiet text rather than a button — it is a
 * rarely-wanted, irreversible sweep, not something to invite — and it is behind
 * a confirmation dialog for the same reason.
 *
 * WHEN IT ARRIVED. Every row carries its own timestamp, on the same line as the
 * category so it costs the card no extra height. The tiering — relative for the
 * first hour, then a clock time, then a date — lives in [NotificationTimeFormat];
 * the zone/locale/12-hour facts are the conversation screens' own
 * [ChatDateContext], reused rather than reinvented so the inbox cannot drift
 * into a third date/time style. A row with no timestamp shows none.
 *
 * The caller removes rows optimistically (see [NotificationsCoordinator]); this
 * screen just renders what it is given, so an empty [state] renders the normal
 * empty state whether the inbox emptied by delete or was never filled.
 *
 * FRIEND REQUESTS: a row announcing an incoming friend request answers it in
 * place, via Accept/Decline, instead of making the user find the Friends page —
 * the notification is where they were told about it, so it is where the choice
 * belongs. [pendingFriendRequestIds] maps a requester uid to their still-pending
 * request id (the live `friend-list` snapshot); a row is actionable only while
 * it resolves through that map, so a request already answered elsewhere shows no
 * buttons at all. See [Notifications.pendingFriendRequestId].
 *
 * The buttons deliberately reflect the SERVER's answer rather than guessing:
 * there is no optimistic "you are now friends" flip, because a refused accept
 * (the pair was blocked meanwhile, the request was withdrawn) would leave the
 * row asserting a friendship that does not exist. The coordinator re-fetches
 * after every response and the row re-derives from the new snapshot.
 *
 * CONVOY INVITES. A convoy-invite row is TAPPABLE and opens the convoy list,
 * where that invite is accepted or declined. Before this it rendered as a plain,
 * inert card: only an UNREAD row was clickable at all, and the only thing that
 * click did was mark it read — so an invite could be read about here but never
 * acted on, which is exactly the "tapping does nothing" report.
 *
 * TITLE. [showTitle] is false when the inbox is a SECTION of something that has
 * already named it — the chat hub, where the member arrived by tapping (or
 * swiping to) a tab labelled "Notifications". A second "Notifications" header
 * directly under that tab says nothing the tab has not already said. It stays
 * true for the standalone full-screen route (a push tap), where the header is
 * the only thing naming the screen.
 *
 * A convoy row is also re-derived against live convoy state ([convoyLink]),
 * because the notification itself is a historical record that is never rewritten
 * — the same staleness [Notifications.pendingFriendRequestId] handles for friend
 * requests. A row whose convoy has ENDED is struck through AND labelled
 * "Konvojen är avslutad" AND carries that in its content description:
 * strikethrough alone is a visual-only signal that a screen reader does not
 * announce and a hurried eye misses. It navigates nowhere, because the only
 * screen it could open would have nothing on it — the explanation is already on
 * the row, which is better than a screen that repeats it. An invite that has
 * merely been ANSWERED keeps its tap (the convoy is alive and worth opening) but
 * says so, so it stops implying something is waiting.
 */
@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    pendingFriendRequestIds: Map<String, String> = emptyMap(),
    busyFriendRequestIds: Set<String> = emptySet(),
    friendActionError: FriendActionError? = null,
    onAcceptFriendRequest: (String) -> Unit = {},
    onDeclineFriendRequest: (String) -> Unit = {},
    onDismissFriendActionError: () -> Unit = {},
    onDeleteNotification: (String) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    deleteError: NotificationDeleteError? = null,
    onDismissDeleteError: () -> Unit = {},
    convoyLink: ConvoyNotificationLink? = null,
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    // Zone, locale and the device's 12/24-hour setting, resolved ONCE for the
    // whole list rather than per row. Reused from the conversation screens
    // wholesale so the inbox cannot drift into a third date/time style.
    val dates = rememberChatDateContext()
    val nowMillis = rememberTickingNow()

    if (confirmDeleteAll) {
        DeleteAllConfirmDialog(
            onDismiss = { confirmDeleteAll = false },
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAll()
            },
        )
    }

    AeroLazyPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = aeroLazyContentPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showTitle) {
                item {
                    AeroPageTitle(stringResource(R.string.notifications_title))
                }
            }

            when (state) {
                NotificationsState.Loading ->
                    item {
                        CircularProgressIndicator()
                    }

                NotificationsState.Error ->
                    item {
                        Text(
                            text = stringResource(R.string.notifications_loadError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                is NotificationsState.Loaded -> {
                    // A failed accept/decline — most often "that request is no
                    // longer available" because it was answered elsewhere.
                    // Dismissible, and shown above the list so it is attached to
                    // the action rather than to any one row (the row it came
                    // from has usually just lost its buttons).
                    friendActionError?.let { error ->
                        item {
                            InboxErrorBanner(
                                text = stringResource(error.messageRes()),
                                onDismiss = onDismissFriendActionError,
                            )
                        }
                    }
                    // A delete the server refused. The row it came from is back
                    // in the list by the time this shows, so the message says
                    // what failed rather than pointing at a row.
                    deleteError?.let { error ->
                        item {
                            InboxErrorBanner(
                                text =
                                    stringResource(
                                        when (error) {
                                            NotificationDeleteError.SINGLE ->
                                                R.string.notifications_deleteError
                                            NotificationDeleteError.ALL ->
                                                R.string.notifications_deleteAllError
                                        },
                                    ),
                                onDismiss = onDismissDeleteError,
                            )
                        }
                    }
                    if (Notifications.unreadCount(state.items) > 0) {
                        item {
                            OutlinedButton(onClick = onMarkAllRead, modifier = Modifier.fillMaxWidth()) {
                                Text(text = stringResource(R.string.notifications_markAllRead))
                            }
                        }
                    }
                    if (state.items.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.notifications_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        item {
                            DeleteAllAction(onClick = { confirmDeleteAll = true })
                        }
                        items(state.items, key = { it.id }) { item ->
                            val requestId =
                                Notifications.pendingFriendRequestId(item, pendingFriendRequestIds)
                            // Re-derived every composition from whatever convoy
                            // facts the shell currently holds, so a convoy that
                            // ends while the inbox is open restyles its rows on
                            // the next snapshot instead of staying inviting.
                            val convoyState =
                                ConvoyNotifications.rowState(
                                    item,
                                    convoyLink?.facts.orEmpty(),
                                )
                            val tapAction = ConvoyNotifications.tapAction(item, convoyState)
                            SwipeToDeleteRow(onDelete = { onDeleteNotification(item.id) }) {
                                NotificationCard(
                                    item = item,
                                    dates = dates,
                                    nowMillis = nowMillis,
                                    onMarkRead = { onMarkRead(item.id) },
                                    friendRequestId = requestId,
                                    friendRequestBusy = requestId != null &&
                                        requestId in busyFriendRequestIds,
                                    onAcceptFriendRequest = onAcceptFriendRequest,
                                    onDeclineFriendRequest = onDeclineFriendRequest,
                                    convoyState = convoyState,
                                    // Null when there is nowhere to go: the card
                                    // then keeps its old behaviour (clickable
                                    // only while unread, to mark read).
                                    onOpen =
                                        convoyLink
                                            ?.takeIf { ConvoyNotifications.navigates(tapAction) }
                                            ?.let { link -> { link.onOpen(tapAction) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wraps one inbox row in a right-to-left swipe that deletes it, revealing a red
 * bin underneath.
 *
 * The gesture is armed in ONE direction only. Both directions would double the
 * chance that a horizontal wobble during a vertical scroll destroys something,
 * and left-to-right in particular overlaps the system back gesture. The
 * positional threshold is half the row's width, so a delete needs a committed
 * drag rather than a flick — the right trade for an action with no undo.
 *
 * [onDelete] fires from `onDismiss`, i.e. once the row has actually been
 * dismissed rather than while it is still being dragged, so a swipe the user
 * releases short of the threshold springs back and deletes nothing. The row
 * then disappears because the CALLER removes it optimistically — and this
 * immediately returns the box to rest, so whether a row is gone is answered by
 * the LIST and never by a "dismissed" flag held here. That is what lets a
 * failed delete put the item straight back with nothing to undo.
 *
 * The identical action is also published as a semantics custom action, because
 * a drag is unreachable for anyone driving the screen with TalkBack.
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val deleteLabel = stringResource(R.string.notifications_deleteAction)
    val scope = rememberCoroutineScope()
    val dismissState =
        rememberSwipeToDismissBoxState(
            positionalThreshold = { totalDistance -> totalDistance * SWIPE_DELETE_THRESHOLD },
        )
    SwipeToDismissBox(
        state = dismissState,
        modifier =
            Modifier
                .testTag(NOTIFICATION_ROW_TEST_TAG)
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label = deleteLabel) {
                                onDelete()
                                true
                            },
                        )
                },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            DeleteSwipeBackground(
                armed = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart,
                contentDescription = deleteLabel,
            )
        },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                // Put the box back at rest right away, so "is this row gone?"
                // is only ever answered by the list. On the normal path the
                // caller has already removed the row and this node is disposed
                // long before a spring-back could be seen; the case it exists
                // for is a delete that fails FAST — quickly enough that the row
                // never actually leaves the list — where without this the row
                // would sit stranded off-screen with nothing left to bring it
                // back.
                scope.launch { dismissState.reset() }
            }
        },
    ) {
        content()
    }
}

/**
 * The red bin behind a swiped row. It is uncovered geometrically — the further
 * the row travels, the more of it shows — and the icon grows once the swipe has
 * passed the threshold, which is the row's way of saying "let go now and this
 * is gone".
 */
@Composable
private fun DeleteSwipeBackground(armed: Boolean, contentDescription: String) {
    val scale by animateFloatAsState(
        targetValue = if (armed) 1.15f else 0.85f,
        label = "notificationDeleteIconScale",
    )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.error)
                .padding(horizontal = KccSpacing.s6),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.scale(scale),
        )
    }
}

/**
 * "Delete all" as small, quiet text. Seb asked for a smaller text rather than a
 * control, and that is also the honest weight for it: emptying the inbox is
 * irreversible and rarely what someone came here to do, so it should be
 * findable without being inviting. Still a TextButton underneath, so it keeps a
 * real touch target and reads to a screen reader as a button.
 */
@Composable
private fun DeleteAllAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = stringResource(R.string.notifications_deleteAll),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Confirmation for the irreversible sweep. Same AlertDialog shape the rest of
 * the app uses for a destructive confirm; the confirming action is tinted with
 * the error colour so the dangerous choice is the one that looks dangerous.
 */
@Composable
private fun DeleteAllConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notifications_deleteAllConfirmTitle)) },
        text = { Text(stringResource(R.string.notifications_deleteAllConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.notifications_deleteAllConfirmAction),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notifications_deleteAllCancel))
            }
        },
    )
}

/** Fraction of a row's width a swipe must cross before it counts as a delete. */
private const val SWIPE_DELETE_THRESHOLD = 0.5f

/**
 * Tag for one swipeable inbox row. The UI test drives the gesture across the
 * ROW's full width; aiming at the title text instead would only span that
 * text's own width, which can fall short of [SWIPE_DELETE_THRESHOLD] and make
 * the test pass or fail on how long a notification's title happens to be.
 */
internal const val NOTIFICATION_ROW_TEST_TAG = "notificationRow"

@Composable
private fun NotificationCard(
    item: AppNotification,
    dates: ChatDateContext,
    nowMillis: Long,
    onMarkRead: () -> Unit,
    friendRequestId: String? = null,
    friendRequestBusy: Boolean = false,
    onAcceptFriendRequest: (String) -> Unit = {},
    onDeclineFriendRequest: (String) -> Unit = {},
    convoyState: ConvoyRowState = ConvoyRowState.NOT_CONVOY,
    onOpen: (() -> Unit)? = null,
) {
    val dead = convoyState.isDead
    // Tapping still marks read exactly as it always did — opening is ADDED to
    // that, never instead of it — so unread/read behaviour is unchanged whether
    // or not the row navigates. A read row becomes clickable only once there is
    // something to open, so a plain read row stays as inert as before.
    val onTap: (() -> Unit)? =
        when {
            onOpen != null -> {
                {
                    if (!item.isRead) onMarkRead()
                    onOpen()
                }
            }
            !item.isRead -> onMarkRead
            else -> null
        }
    val clickModifier = onTap?.let { Modifier.clickable(onClick = it) } ?: Modifier
    // One description for the whole card, so an ended convoy is ANNOUNCED as
    // ended rather than left to a strikethrough a screen reader cannot see.
    val endedDescription =
        stringResource(R.string.notifications_convoyEndedRowDescription, item.title)
    val deadSemantics =
        if (dead) {
            Modifier.semantics(mergeDescendants = true) { contentDescription = endedDescription }
        } else {
            Modifier
        }
    Card(
        modifier = Modifier.fillMaxWidth().then(clickModifier).then(deadSemantics),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (item.isRead) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Category on the left, arrival time on the right of the SAME line:
            // the timestamp is metadata about the row, so it shares the row's
            // metadata line instead of costing the card another one.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(item.category.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    // The timestamp is the unweighted child, so a Row measures
                    // it FIRST at its full width and leaves the category
                    // whatever is left. At a large font scale that remainder
                    // can be a few dp, and an unconstrained label would then
                    // wrap down the card a character at a time. One line,
                    // ellipsised — the full string still reaches TalkBack, so
                    // nothing is actually lost when it does have to truncate.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NotificationTimeText(
                    createdAtMillis = item.createdAtMillis,
                    dates = dates,
                    nowMillis = nowMillis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color =
                    if (dead) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                textDecoration = if (dead) TextDecoration.LineThrough else null,
            )
            // The label the strikethrough cannot say out loud. Both, always —
            // never one or the other.
            when (convoyState) {
                ConvoyRowState.ENDED ->
                    Text(
                        text = stringResource(R.string.notifications_convoyEndedLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                ConvoyRowState.INVITE_ANSWERED ->
                    Text(
                        text = stringResource(R.string.notifications_convoyInviteAnsweredLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> Unit
            }
            (item.previewText ?: item.body)?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.isRead) {
                Text(
                    text = stringResource(R.string.notifications_unreadLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Only for a friend request that is STILL pending — the caller
            // resolved it against the live snapshot, so a request answered on
            // another device simply arrives here as null and renders nothing.
            if (friendRequestId != null) {
                Row(
                    modifier = Modifier.padding(top = KccSpacing.s2),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    // Disabled while this request's callable is in flight, so
                    // rapid taps can't start overlapping mutations (and can't
                    // race an accept against a decline). Mirrors the Friends
                    // page's incoming-request row.
                    Button(
                        onClick = { onAcceptFriendRequest(friendRequestId) },
                        enabled = !friendRequestBusy,
                    ) {
                        Text(stringResource(R.string.friends_accept))
                    }
                    OutlinedButton(
                        onClick = { onDeclineFriendRequest(friendRequestId) },
                        enabled = !friendRequestBusy,
                    ) {
                        Text(stringResource(R.string.friends_decline))
                    }
                }
            }
        }
    }
}

/**
 * When a notification arrived, as small quiet text on the row's metadata line.
 *
 * Renders NOTHING when there is no timestamp. `createdAt` is a server timestamp,
 * so an item that was just written is momentarily readable with the field still
 * unset; a placeholder there would be a flicker, and falling back to the epoch
 * would have the row claim it arrived in 1970.
 *
 * Deliberately subordinate to the message: `labelSmall` on `onSurfaceVariant`,
 * so it is the last thing the eye lands on and never competes with the
 * notification's own title.
 */
@Composable
private fun NotificationTimeText(
    createdAtMillis: Long?,
    dates: ChatDateContext,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val label = NotificationTimeFormat.label(createdAtMillis, nowMillis, dates.zone) ?: return
    val text =
        when (label) {
            // The relative tier prints no clock at all, so it must not pay for
            // one — rememberClockTime is called only in the branches below that
            // actually show a time.
            is NotificationTimeLabel.JustNow -> stringResource(R.string.notifications_timeJustNow)
            is NotificationTimeLabel.MinutesAgo ->
                stringResource(R.string.notifications_timeMinutesAgo, label.minutes)
            is NotificationTimeLabel.Today ->
                stringResource(
                    R.string.notifications_timeToday,
                    rememberClockTime(label.millis, dates),
                )
            is NotificationTimeLabel.Yesterday ->
                stringResource(
                    R.string.notifications_timeYesterday,
                    rememberClockTime(label.millis, dates),
                )
            is NotificationTimeLabel.Absolute -> {
                // The month/day ORDER is locale copy, so it comes from the
                // contract's pattern rather than being spelled out here; the
                // month NAME then comes from CLDR via the matching locale.
                val pattern =
                    stringResource(
                        if (label.includeYear) {
                            R.string.notifications_timeDatePatternWithYear
                        } else {
                            R.string.notifications_timeDatePattern
                        },
                    )
                val date = ChatDateFormat.format(label.date, pattern, dates.locale)
                stringResource(
                    R.string.notifications_timeDateAndTime,
                    date,
                    rememberClockTime(label.millis, dates),
                )
            }
        }
    // Read aloud, "22 jul 14:05" in a stream of row text does not say what it is.
    val spoken = stringResource(R.string.notifications_timeReceivedAt, text)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

/**
 * The clock time for one instant, computed once per row rather than on every
 * recomposition.
 *
 * Keyed on exactly the fields [ChatDateFormat.time] reads, so the ticking `now`
 * can re-run the (cheap) tier decision every minute without also rebuilding
 * every visible row's `DateTimeFormatter`. The key deliberately does NOT include
 * `dates.today`, which rolls over at midnight and would otherwise invalidate
 * every row's clock string for no reason.
 */
@Composable
private fun rememberClockTime(millis: Long, dates: ChatDateContext): String =
    remember(millis, dates.zone, dates.locale, dates.use24Hour) {
        ChatDateFormat.time(
            millis = millis,
            zone = dates.zone,
            locale = dates.locale,
            use24Hour = dates.use24Hour,
        )
    }

/**
 * `System.currentTimeMillis()`, refreshed once a minute.
 *
 * The relative tier is the reason this exists: without it a row that said
 * "Nu" when the inbox opened would still say "Nu" ten minutes later, which is
 * simply false. A minute is also the resolution the labels are printed at, so
 * ticking faster could not change what any row says.
 */
@Composable
private fun rememberTickingNow(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(NotificationTimeFormat.MINUTE_MILLIS)
        }
    }
    return now
}

/**
 * Dismissible error for anything the inbox tried and the server refused — a
 * stale accept/decline, or a delete that did not go through. Mirrors the
 * Friends page's ErrorBanner (same shape, same error colours) rather than
 * importing it, which is private to that screen.
 */
@Composable
private fun InboxErrorBanner(text: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                // The inbox's own dismiss label: this banner is no longer only
                // about friend requests, so it should not borrow the Friends
                // domain's string.
                Text(stringResource(R.string.notifications_errorDismiss))
            }
        }
    }
}
