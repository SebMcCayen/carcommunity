package com.kungsbackacarcommunity.app.chattime

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Everything a conversation needs to render dates and times, resolved once per
 * screen rather than per message.
 *
 * @param zone the DEVICE's zone. Messages are stored as absolute instants, so
 *   every calendar decision here has to be made in the reader's own zone.
 * @param locale the configuration's locale, which is also the locale the day/month
 *   name patterns were resolved from — so the pattern and the names it formats
 *   can never come from two different languages.
 * @param use24Hour the device's clock setting, which the user sets independently
 *   of their locale.
 * @param today the reader's current local day, driving "Today"/"Yesterday".
 */
@Immutable
data class ChatDateContext(
    val zone: ZoneId,
    val locale: Locale,
    val use24Hour: Boolean,
    val today: LocalDate,
)

/**
 * Builds a [ChatDateContext] and keeps `today` honest: a conversation left open
 * across midnight would otherwise keep labelling yesterday's messages "Today"
 * until something unrelated recomposed. The effect sleeps exactly until the next
 * local midnight and then rolls the day over.
 */
@Composable
fun rememberChatDateContext(): ChatDateContext {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // LocalLocale, not configuration.locales[0]: the composition local is
    // OBSERVABLE, so a locale change recomposes the conversation instead of
    // leaving last language's month names on screen (lint: NonObservableLocale).
    val locale = LocalLocale.current.platformLocale
    val use24Hour = DateFormat.is24HourFormat(context)
    val zone = remember(configuration) { ZoneId.systemDefault() }

    val today by produceState(initialValue = LocalDate.now(zone), zone) {
        while (true) {
            value = LocalDate.now(zone)
            val nextMidnight = value.plusDays(1).atStartOfDay(zone).toInstant()
            // Plus a second so a tiny early wake-up doesn't re-read the same day
            // and busy-loop until the boundary actually passes.
            delay(ChronoUnit.MILLIS.between(java.time.Instant.now(), nextMidnight) + 1_000L)
        }
    }

    return ChatDateContext(zone = zone, locale = locale, use24Hour = use24Hour, today = today)
}

/** The text a separator shows for [date] — "Today", "Yesterday", or the spelled-out day. */
@Composable
fun daySeparatorText(date: LocalDate, dates: ChatDateContext): String =
    when (val label = ChatDateFormat.label(date, dates.today)) {
        DaySeparatorLabel.Today -> stringResource(R.string.chatTime_today)
        DaySeparatorLabel.Yesterday -> stringResource(R.string.chatTime_yesterday)
        is DaySeparatorLabel.Absolute -> {
            val pattern =
                stringResource(
                    if (label.includeYear) {
                        R.string.chatTime_daySeparatorFormatWithYear
                    } else {
                        R.string.chatTime_daySeparatorFormat
                    },
                )
            ChatDateFormat.format(label.date, pattern, dates.locale)
        }
    }

/**
 * The centred date heading above a day's messages: a quiet pill, the same idiom
 * every messaging app uses, so it reads as structure rather than as a message.
 */
@Composable
fun DaySeparatorRow(date: LocalDate, dates: ChatDateContext, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = KccSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(KccRadius.md),
        ) {
            Text(
                text = daySeparatorText(date, dates),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s1),
            )
        }
    }
}

/**
 * The per-message time stamp.
 *
 * Renders NOTHING for a message with no timestamp — an optimistic local echo
 * still waiting on the server clock. A placeholder there would only be a lie that
 * flickers.
 *
 * Placed by callers on the line ABOVE the bubble (beside the sender's name where
 * there is one), aligned to the bubble's own side.
 */
@Composable
fun MessageTimeText(millis: Long?, dates: ChatDateContext, modifier: Modifier = Modifier) {
    if (millis == null) return
    val time =
        remember(millis, dates) {
            ChatDateFormat.time(
                millis = millis,
                zone = dates.zone,
                locale = dates.locale,
                use24Hour = dates.use24Hour,
            )
        }
    // "19:05" on its own is meaningless read aloud mid-conversation; the label
    // says what the number is.
    val spoken = stringResource(R.string.chatTime_messageSentAt, time)
    Text(
        text = time,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}
