package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.events.EventsListState
import com.kungsbackacarcommunity.app.events.FirebaseEventsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

internal fun clubCalendarDays(month: YearMonth): List<LocalDate?> {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val size = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return List(size) { index -> (index - offset + 1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay) }
}

@Composable
internal fun ClubCalendar(repository: ClubRepository, clubId: String, canManage: Boolean, onOpenEvent: (String) -> Unit) {
    val locale = clubLocale()
    var month by remember { mutableStateOf(YearMonth.now(clubZone)) }
    var page by remember { mutableStateOf(ClubPage<ClubEvent>(emptyList(), null)) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    var chooseEvent by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(clubId, refresh) {
        try { page = repository.events(clubId); error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
    }
    Text(stringResource(R.string.clubs_calendar), style = MaterialTheme.typography.titleMedium)
    ClubCalendarSubscriptionControl(clubId)
    Card(Modifier.fillMaxWidth(), colors = clubCardColors()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.clubs_previousMonth)) }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.clubs_nextMonth)) }
        }
        Row(Modifier.fillMaxWidth()) { DayOfWeek.entries.forEach { day -> Text(day.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f), textAlign = TextAlign.Center) } }
        val dates = page.items.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(clubZone).toLocalDate() }
        clubCalendarDays(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) { week.forEach { date ->
                Surface(Modifier.weight(1f).padding(2.dp), color = if (dates.containsKey(date)) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Text(date?.dayOfMonth?.toString().orEmpty(), Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center)
                }
            } }
        }
        val monthEvents = page.items.filter { YearMonth.from(Instant.ofEpochMilli(it.startMillis).atZone(clubZone)) == month }
        if (monthEvents.isEmpty()) Text(stringResource(R.string.clubs_calendarEmpty))
        monthEvents.forEach { event ->
            TextButton(onClick = { onOpenEvent(event.eventId) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.clubs_monthEvent, clubDate(event.startMillis), event.title)) }
            if (canManage) TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                busy = true; scope.launch {
                    try { repository.setEventAttendance(clubId, event.eventId, false, UUID.randomUUID().toString()); refresh++ }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.clubs_unattend)) }
        }
        if (page.nextCursor != null) TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try { val next = repository.events(clubId, page.nextCursor); page = ClubPage((page.items + next.items).distinctBy { it.eventId }, next.nextCursor) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.clubs_more)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(clubError(it), color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.clubs_retry)) } }
        if (canManage) OutlinedButton(onClick = { chooseEvent = true }, enabled = !busy) { Text(stringResource(R.string.clubs_chooseEvent)) }
    } }
    if (chooseEvent) ClubEventPicker(onDismiss = { chooseEvent = false }, onSelect = { eventId ->
        chooseEvent = false; busy = true; scope.launch {
            try { repository.setEventAttendance(clubId, eventId, true, UUID.randomUUID().toString()); refresh++ }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
            finally { busy = false }
        }
    })
}

@Composable
private fun ClubEventPicker(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { FirebaseEventsRepository.createIfAvailable(context) }
    val source = remember(repo) { repo?.observePublishedEvents() ?: kotlinx.coroutines.flow.flowOf(EventsListState.Error()) }
    val state by source.collectAsState(initial = EventsListState.Loading)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.clubs_chooseEvent)) }, text = { Column {
        Text(stringResource(R.string.clubs_publicAttendance), style = MaterialTheme.typography.bodySmall)
        when (val loaded = state) {
            is EventsListState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is EventsListState.Error -> Text(stringResource(R.string.clubs_error))
            is EventsListState.Loaded -> {
                val events = loaded.events.filter { (it.endsAtMillis ?: it.startsAtMillis ?: 0) > System.currentTimeMillis() }
                if (events.isEmpty()) Text(stringResource(R.string.clubs_noEvents))
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(events, key = { it.id }) { event ->
                    TextButton(onClick = { onSelect(event.id) }) { Column { Text(event.title); event.startsAtMillis?.let { Text(clubDate(it), style = MaterialTheme.typography.bodySmall) } } }
                } }
            }
        }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clubs_close)) } })
}
