package com.kungsbackacarcommunity.app.partners

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccButton as Button
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class PortalOperation {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Int?>(null)
    suspend fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = portalFailureMessage(e) }
        finally { busy = false }
    }
}
internal fun portalFailureMessage(error: Exception): Int = if (error is IllegalArgumentException || error is java.time.format.DateTimeParseException) R.string.partnerPortal_invalidFields else when ((error as? FirebaseFunctionsException)?.code) {
    FirebaseFunctionsException.Code.PERMISSION_DENIED, FirebaseFunctionsException.Code.UNAUTHENTICATED -> R.string.partnerPortal_accessDenied
    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> R.string.partnerPortal_full
    FirebaseFunctionsException.Code.FAILED_PRECONDITION -> R.string.partnerPortal_unavailable
    FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> R.string.partnerPortal_invalidToken
    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> R.string.partnerPortal_invalidFields
    else -> R.string.partnerPortal_error
}
@Composable
internal fun PortalOperationFeedback(operation: PortalOperation) {
    if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    operation.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}
@Composable
internal fun PortalStatus(status: String) {
    val label = when(status) {
        "submitted" -> R.string.partnerPortal_submitted
        "under_review" -> R.string.partnerPortal_underReview
        "changes_requested" -> R.string.partnerPortal_changesRequested
        "approved" -> R.string.partnerPortal_approved
        "rejected" -> R.string.partnerPortal_rejected
        "draft" -> R.string.partnerPortal_draft
        "active" -> R.string.partnerPortal_active
        "paused" -> R.string.partnerPortal_paused
        "ended" -> R.string.partnerPortal_ended
        "reserved" -> R.string.partnerPortal_reserved
        "redeemed" -> R.string.partnerPortal_redeemed
        "cancelled" -> R.string.partnerPortal_cancelled
        "expired" -> R.string.partnerPortal_expired
        "revoked" -> R.string.partnerPortal_revoked
        else -> R.string.partnerPortal_unavailable
    }
    Text(stringResource(label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
}
internal fun portalDate(iso: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC).format(Instant.parse(iso))
}.getOrDefault(iso)

internal fun portalDateTime(iso: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault(iso)

/** Personal recent reservations. The API never returns another customer's records. */
@Composable
fun PartnerReservationsRoute(repository: PartnerPortalRepository, onBack: () -> Unit) {
    var reservations by remember { mutableStateOf<List<PartnerReservation>?>(null) }
    var selected by remember { mutableStateOf<PartnerReservation?>(null) }
    val operation = remember { PortalOperation() }; val scope = rememberCoroutineScope()
    suspend fun reload() { reservations = repository.reservations() }
    LaunchedEffect(repository) { operation.run { reload() } }
    val reservation = selected
    BackHandler { if (selected != null) selected = null else onBack() }
    if (reservation != null) {
        PartnerReservationScreen(repository, reservation, onBack = { selected = null }, onChanged = { updated ->
            selected = updated; reservations = reservations?.map { if (it.reservationId == updated.reservationId) updated else it }
        })
        return
    }
    AeroPage(title = stringResource(R.string.partnerPortal_reservations), ownerShortcutEnabled = false, compactTitleWithBack = true) {
        PortalOperationFeedback(operation)
        if (operation.error != null) Button(onClick = { scope.launch { operation.run { reload() } } }) { Text(stringResource(R.string.partnerPortal_retry)) }
        if (reservations?.isEmpty() == true) Text(stringResource(R.string.partnerPortal_emptyReservations))
        reservations.orEmpty().forEach { row ->
            OutlinedCard(onClick = { selected = row }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(row.companyName); PortalStatus(row.status)
                    Text(stringResource(R.string.partnerPortal_expires, portalDateTime(row.expiresAt)))
                }
            }
        }
    }
}

@Composable
fun PartnerReserveOfferRoute(repository: PartnerPortalRepository, offerId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope(); val operation = remember(offerId) { PortalOperation() }
    var detail by remember(offerId) { mutableStateOf<PartnerOfferReservationDetail?>(null) }
    var reservation by remember(offerId) { mutableStateOf<PartnerReservation?>(null) }
    // Retained through network failures; retrying the same user action cannot consume a second slot.
    var requestId by remember(offerId) { mutableStateOf(UUID.randomUUID().toString()) }
    LaunchedEffect(repository, offerId) { operation.run { detail = repository.offer(offerId); reservation = detail?.reservation } }
    val current = reservation
    if (current != null && current.status !in setOf("cancelled", "expired")) {
        PartnerReservationScreen(repository, current, onBack, onChanged = { updated ->
            reservation = updated
            if (updated.status in setOf("cancelled", "expired")) {
                requestId = UUID.randomUUID().toString()
                scope.launch { operation.run { detail = repository.offer(offerId) } }
            }
        })
        return
    }
    AeroPage(title = detail?.offer?.title ?: stringResource(R.string.partnerPortal_reserve), ownerShortcutEnabled = false, compactTitleWithBack = true) {
        PortalOperationFeedback(operation)
        val offer = detail?.offer
        if (offer == null) {
            if (!operation.busy) Button(onClick = { scope.launch { operation.run { detail = repository.offer(offerId); reservation = detail?.reservation } } }) { Text(stringResource(R.string.partnerPortal_retry)) }
        } else {
            Text(offer.description, style = MaterialTheme.typography.bodyLarge)
            Text(offer.availableUntil?.let { stringResource(R.string.partnerPortal_validUntil, portalDateTime(it)) } ?: stringResource(R.string.partnerPortal_untilFurther))
            Text(stringResource(R.string.partnerPortal_reservationWindow, offer.reservationDays))
            Text(stringResource(R.string.partnerPortal_terms), style = MaterialTheme.typography.titleMedium)
            Text(offer.terms)
            Text(if (offer.capacity == null) stringResource(R.string.partnerPortal_unlimited) else stringResource(R.string.partnerPortal_remaining, (offer.remainingCount ?: 0).coerceAtLeast(0), offer.capacity))
            Text(stringResource(R.string.partnerPortal_onePerAccount))
            Text(stringResource(R.string.partnerPortal_reservationStatsNotice))
            Text(stringResource(R.string.partnerPortal_noAppointment))
            Text(stringResource(R.string.partnerPortal_noPayment))
            if (detail?.alreadyUsed == true) {
                Text(stringResource(R.string.partnerPortal_alreadyUsed))
            } else {
                Button(onClick = { scope.launch { operation.run { reservation = repository.reserve(offerId, requestId, offer.version) } } }, enabled = !operation.busy && !offer.reservationsPaused, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_reserve)) }
            }
        }
    }
}

@Composable
private fun PartnerReservationScreen(repository: PartnerPortalRepository, reservation: PartnerReservation, onBack: () -> Unit, onChanged: (PartnerReservation) -> Unit) {
    val scope = rememberCoroutineScope(); val operation = remember(reservation.reservationId) { PortalOperation() }
    var token by remember(reservation.reservationId) { mutableStateOf<PartnerReservationToken?>(null) }
    var cancelConfirmation by remember { mutableStateOf(false) }
    var elapsedNow by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var tokenDeadlineElapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(token, reservation.status) { while(true) { elapsedNow = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    // Server owns reservation expiry. A device with an incorrect wall clock may still ask
    // the server for its QR; the projected reservation status is authoritative.
    val usable = reservation.status == "reserved"
    val tokenSeconds = if (token == null) 0 else partnerQrSecondsRemaining(tokenDeadlineElapsed, elapsedNow)
    // Short-lived bearer tokens stay in memory and are never copied to saved state or logs.
    PartnerSensitiveWindowEffect()
    BackHandler(onBack = onBack)
    AeroPage(title = reservation.title, ownerShortcutEnabled = false, compactTitleWithBack = true) {
        Text(reservation.companyName, style = MaterialTheme.typography.titleMedium)
        PortalStatus(reservation.status)
        Text(stringResource(R.string.partnerPortal_expires, portalDateTime(reservation.expiresAt)))
        Text(stringResource(R.string.partnerPortal_reference, reservation.reservationId.takeLast(10)))
        Text(reservation.terms)
        Text(stringResource(R.string.partnerPortal_noAppointment))
        Text(stringResource(R.string.partnerPortal_noPayment))
        PortalOperationFeedback(operation)
        if (usable) {
            if (token != null && tokenSeconds > 0) {
                PartnerQrImage(requireNotNull(token).token)
                Text(stringResource(R.string.partnerPortal_qrRemaining, tokenSeconds))
                // Same bearer token as the QR: accessible manual fallback, never a reusable discount code.
                Text(requireNotNull(token).token, style = MaterialTheme.typography.bodySmall)
            } else if (token != null) Text(stringResource(R.string.partnerPortal_qrExpired))
            Text(stringResource(R.string.partnerPortal_qrHint))
            Button(onClick = { scope.launch { operation.run { token = null
                // Conservative 60-second deadline measured from request START, never phone date/time.
                val requestedAt = android.os.SystemClock.elapsedRealtime()
                val issued = repository.token(reservation.reservationId)
                tokenDeadlineElapsed = requestedAt + 60_000L
                elapsedNow = android.os.SystemClock.elapsedRealtime()
                token = issued } } }, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (token == null) R.string.partnerPortal_showQr else R.string.partnerPortal_refreshQr))
            }
            OutlinedButton(onClick = { cancelConfirmation = true }, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_cancelReservation)) }
        }
    }
    if (cancelConfirmation) AlertDialog(onDismissRequest = { if (!operation.busy) cancelConfirmation = false },
        text = { Text(stringResource(R.string.partnerPortal_cancelConfirm)) },
        confirmButton = { TextButton(enabled = !operation.busy, onClick = { scope.launch { operation.run { val updated = repository.cancel(reservation.reservationId); token = null; cancelConfirmation = false; onChanged(updated) } } }) { Text(stringResource(R.string.partnerPortal_confirm)) } },
        dismissButton = { TextButton(onClick = { cancelConfirmation = false }) { Text(stringResource(R.string.partnerPortal_cancel)) } })
}

@Composable
private fun PartnerQrImage(token: String) {
    val bitmap by produceState<Bitmap?>(null, token) {
        value = withContext(Dispatchers.Default) {
            val matrix = MultiFormatWriter().encode(token, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(640 * 640) { index -> if (matrix[index % 640, index / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888)
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.partnerPortal_showQr), modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(1f)) }
}
@Composable
private fun PartnerSensitiveWindowEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.portalActivity()?.window
        val wasSecure = window != null && window.attributes.flags.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
private fun Context.portalActivity(): Activity? = when(this) { is Activity -> this; is ContextWrapper -> baseContext.portalActivity(); else -> null }

/** Managers see only their business; redeemers only get the scanner. */
@Composable
fun PartnerWorkspaceRoute(repository: PartnerPortalRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val operation = remember { PortalOperation() }
    var workspace by remember { mutableStateOf<PartnerWorkspace?>(null) }
    var company by remember { mutableStateOf<PartnerWorkspaceCompany?>(null) }
    var scanner by remember { mutableStateOf(false) }
    LaunchedEffect(repository) { operation.run { workspace = repository.workspace() } }
    BackHandler { when { scanner -> scanner = false; company != null -> company = null; else -> onBack() } }
    if (scanner) { PartnerRedemptionRoute(repository, company, onBack = { scanner = false }); return }
    val current = company
    if (current != null) {
        PartnerManagedCompanyScreen(repository, current, onBack = { company = null }, onScan = { scanner = true })
        return
    }
    AeroPage(title = stringResource(R.string.partnerPortal_manage), ownerShortcutEnabled = false, compactTitleWithBack = true) {
        PortalOperationFeedback(operation)
        if (operation.error != null) Button(onClick = { scope.launch { operation.run { workspace = repository.workspace() } } }) { Text(stringResource(R.string.partnerPortal_retry)) }
        if (workspace?.companies?.isEmpty() == true) Text(stringResource(R.string.partnerPortal_noBusinesses))
        workspace?.companies.orEmpty().forEach { row ->
            OutlinedCard(onClick = { company = row }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PartnerLogo(row.logoPath)
                    Column { Text(row.name, style = MaterialTheme.typography.titleMedium); PortalStatus(row.status) }
                }
            }
        }
    }
}

@Composable
private fun PartnerManagedCompanyScreen(repository: PartnerPortalRepository, company: PartnerWorkspaceCompany, onBack: () -> Unit, onScan: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var offers by remember { mutableStateOf<List<ManagedPartnerOffer>?>(null) }
    var editing by remember { mutableStateOf<ManagedPartnerOffer?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var statistics by remember { mutableStateOf<PartnerAggregateStatistics?>(null) }
    var period by remember { mutableStateOf("all") }
    var description by remember { mutableStateOf(company.description) }
    var phone by remember { mutableStateOf(company.phone.orEmpty()) }
    var website by remember { mutableStateOf(company.website.orEmpty()) }
    var logoPath by remember { mutableStateOf(company.logoPath) }
    var pendingLogo by remember { mutableStateOf<String?>(null) }
    var logoRights by remember { mutableStateOf(false) }
    var profileSaved by remember { mutableStateOf(false) }
    val operation = remember { PortalOperation() }; val scope = rememberCoroutineScope()
    val manager = company.role == "manager"
    val logoPicker = rememberImagePickLauncher(maxBytes = PartnerLogoEncoding.MAX_BYTES, onPickFailed = { operation.error = R.string.partnerPortal_logoError }) { image ->
        if (image != null) operation.run {
            pendingLogo = PartnerLogoEncoding.encode(image)
            logoRights = false
        }
    }
    LaunchedEffect(company.companyId, tab, period) {
        if (manager) operation.run {
            if (tab == 0) offers = repository.offers(company.companyId)
            if (tab == 2) statistics = repository.statistics(company.companyId, period)
        }
    }
    BackHandler { if (editorOpen) editorOpen = false else onBack() }
    if (editorOpen) {
        PartnerOfferEditor(repository, company.companyId, editing, onBack = { editorOpen = false }, onSaved = {
            editorOpen = false; scope.launch { operation.run { offers = repository.offers(company.companyId) } }
        }); return
    }
    AeroPage(title = company.name, ownerShortcutEnabled = false, compactTitleWithBack = true) {
        PartnerLogo(logoPath, Modifier.size(96.dp)); PortalStatus(company.status)
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_scan)) }
        if (!manager) return@AeroPage
        TabRow(selectedTabIndex = tab) {
            listOf(R.string.partnerPortal_offers, R.string.partnerPortal_profile, R.string.partnerPortal_statistics).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
            }
        }
        PortalOperationFeedback(operation)
        when(tab) {
            0 -> {
                Text(stringResource(R.string.partnerPortal_aggregateHint))
                Button(onClick = { editing = null; editorOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_newOffer)) }
                if (offers?.isEmpty() == true) Text(stringResource(R.string.partnerPortal_emptyOffers))
                offers.orEmpty().forEach { offer ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(offer.title, style = MaterialTheme.typography.titleMedium); PortalStatus(offer.status)
                        Text(stringResource(R.string.partnerPortal_usedCount, offer.redeemedCount))
                        if (offer.capacity != null && offer.remainingCount != null) Text(stringResource(R.string.partnerPortal_remaining, offer.remainingCount, offer.capacity))
                        if (offer.status == "draft" && offer.reservedCount == 0 && offer.redeemedCount == 0) {
                            OutlinedButton(onClick = { editing = offer; editorOpen = true }, enabled = !operation.busy) { Text(stringResource(R.string.partnerPortal_editOffer)) }
                        }
                        if (offer.status == "active") {
                            Text(stringResource(R.string.partnerPortal_pauseHint))
                            OutlinedButton(onClick = { scope.launch { operation.run { repository.pause(company.companyId, offer.offerId, !offer.reservationsPaused); offers = repository.offers(company.companyId) } } }, enabled = !operation.busy) { Text(stringResource(if (offer.reservationsPaused) R.string.partnerPortal_resume else R.string.partnerPortal_pause)) }
                        }
                    } }
                }
                if (operation.error != null) OutlinedButton(onClick = { scope.launch { operation.run { offers = repository.offers(company.companyId) } } }) { Text(stringResource(R.string.partnerPortal_retry)) }
            }
            1 -> {
                PortalField(description, R.string.partnerPortal_description, multiline = true) { description = it.take(2000); profileSaved = false }
                PortalField(phone, R.string.partnerPortal_publicPhone) { phone = it.take(30); profileSaved = false }
                PortalField(website, R.string.partners_websiteLabel) { website = it.take(500); profileSaved = false }
                Button(onClick = { scope.launch { operation.run { repository.updateProfile(company.companyId, description.trim(), phone.trim(), website.trim()); profileSaved = true } } }, enabled = !operation.busy && description.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_saveProfile)) }
                if (profileSaved) Text(stringResource(R.string.partnerPortal_saved))
                Text(stringResource(R.string.partnerPortal_logoHint))
                OutlinedButton(onClick = { logoPicker.pickImage() }, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_logo)) }
                pendingLogo?.let { selectedLogo ->
                    PartnerLogoPreview(selectedLogo)
                    PortalToggle(logoRights, R.string.partnerPortal_logoRights) { logoRights = it }
                    Button(onClick = { scope.launch { operation.run {
                        repository.uploadLogo(company.companyId, selectedLogo)
                        logoPath = repository.workspace().companies.firstOrNull { it.companyId == company.companyId }?.logoPath
                        pendingLogo = null; logoRights = false
                    } } }, enabled = !operation.busy && logoRights, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_publishLogo)) }
                }
            }
            2 -> {
                Text(stringResource(R.string.partnerPortal_aggregateHint))
                val months = remember { (1L..6L).map { YearMonth.now(ZoneOffset.UTC).minusMonths(it).toString() } }
                var dropdown by remember { mutableStateOf(false) }
                Box { OutlinedButton(onClick = { dropdown = true }) { Text(if (period == "all") stringResource(R.string.partnerPortal_allTime) else period) }
                    DropdownMenu(expanded = dropdown, onDismissRequest = { dropdown = false }) {
                        (listOf("all") + months).forEach { item -> DropdownMenuItem(text = { Text(if (item == "all") stringResource(R.string.partnerPortal_allTime) else item) }, onClick = { statistics = null; period = item; dropdown = false }) }
                    }
                }
                statistics?.let { data ->
                    Text(stringResource(R.string.partnerPortal_usedCount, data.totalRedeemed), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.partnerPortal_updated, portalDate(data.updatedAt)))
                    data.offers.forEach { offer -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(offer.title, Modifier.weight(1f)); Text(offer.totalRedeemed.toString()) } }
                }
                if (statistics == null && !operation.busy && operation.error == null) Text(stringResource(R.string.partnerPortal_noStatistics))
                if (operation.error != null) OutlinedButton(onClick = { scope.launch { operation.run { statistics = repository.statistics(company.companyId, period) } } }) { Text(stringResource(R.string.partnerPortal_retry)) }
            }
        }
    }
}

@Composable
private fun PartnerOfferEditor(repository: PartnerPortalRepository, companyId: String, offer: ManagedPartnerOffer?, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope(); val operation = remember { PortalOperation() }
    var title by remember { mutableStateOf(offer?.title.orEmpty()) }; var teaser by remember { mutableStateOf(offer?.teaserText.orEmpty()) }
    var description by remember { mutableStateOf(offer?.description.orEmpty()) }; var terms by remember { mutableStateOf(offer?.terms.orEmpty()) }
    var capacity by remember { mutableStateOf(offer?.capacity?.toString().orEmpty()) }; var days by remember { mutableStateOf((offer?.reservationDays ?: 7).toString()) }
    var percentage by remember { mutableStateOf(offer?.percentageDiscount?.toString().orEmpty()) }
    var start by remember { mutableStateOf(offer?.availableFrom?.take(10).orEmpty()) }; var end by remember { mutableStateOf(partnerOfferEndDate(offer?.availableUntil)) }
    AeroPage(title = stringResource(if (offer == null) R.string.partnerPortal_newOffer else R.string.partnerPortal_editOffer), ownerShortcutEnabled = false, compactTitleWithBack = true, contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars)) {
        Text(stringResource(R.string.partnerPortal_draftHint))
        PortalField(title, R.string.partnerPortal_title) { title = it.take(120) }
        PortalField(teaser, R.string.partnerPortal_teaser) { teaser = it.take(280) }
        PortalField(description, R.string.partnerPortal_description, multiline = true) { description = it.take(4000) }
        PortalField(terms, R.string.partnerPortal_terms, multiline = true) { terms = it.take(4000) }
        PortalField(percentage, R.string.partnerPortal_percent, androidx.compose.ui.text.input.KeyboardType.Number) { percentage = it.take(3) }
        PortalField(capacity, R.string.partnerPortal_capacity, androidx.compose.ui.text.input.KeyboardType.Number) { capacity = it.take(6) }
        PortalField(days, R.string.partnerPortal_days, androidx.compose.ui.text.input.KeyboardType.Number) { days = it.take(2) }
        PortalField(start, R.string.partnerPortal_start) { start = it.take(10) }
        PortalField(end, R.string.partnerPortal_end) { end = it.take(10) }
        Text(stringResource(R.string.partnerPortal_noAppointment)); Text(stringResource(R.string.partnerPortal_noPayment))
        PortalOperationFeedback(operation)
        Button(onClick = { scope.launch { operation.run {
            val fields = partnerOfferFields(title, teaser, description, terms, percentage, capacity, days, start, end)
            repository.submitOffer(companyId, fields + if (offer != null) mapOf("offerId" to offer.offerId) else emptyMap())
            onSaved()
        } } }, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_saveDraft)) }
        OutlinedButton(onClick = onBack, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_cancel)) }
    }
}

/** The API end is exclusive; the date field shows the last included UTC day. */
internal fun partnerOfferEndDate(availableUntil: String?): String = availableUntil?.let {
    runCatching { Instant.parse(it).minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate().toString() }.getOrDefault("")
}.orEmpty()

internal fun partnerOfferFields(title: String, teaser: String, description: String, terms: String, percent: String, capacity: String, days: String, start: String, end: String): Map<String, Any?> {
    require(title.trim().length in 2..120 && teaser.trim().length in 2..300 && description.trim().length in 2..6000 && terms.trim().length in 2..4000)
    val capacityValue = if (capacity.isBlank()) null else requireNotNull(capacity.toIntOrNull()).also { require(it in 1..100000) }
    val percentageValue = if (percent.isBlank()) null else requireNotNull(percent.toIntOrNull()).also { require(it in 1..100) }
    val dayValue = requireNotNull(days.toIntOrNull()).also { require(it in 1..30) }
    val from = start.ifBlank { null }?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }
    val until = end.ifBlank { null }?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() }
    require(from == null || until == null || until > from)
    return mapOf("title" to title.trim(), "teaserText" to teaser.trim(), "description" to description.trim(), "terms" to terms.trim(), "percentageDiscount" to percentageValue, "capacity" to capacityValue, "reservationDays" to dayValue, "availableFrom" to from?.toString(), "availableUntil" to until?.toString())
}

@Composable
internal fun PartnerRedemptionRoute(repository: PartnerPortalRepository, company: PartnerWorkspaceCompany?, onBack: () -> Unit) {
    val context = LocalContext.current; val operation = remember { PortalOperation() }; val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }; var token by remember { mutableStateOf<String?>(null) }
    var inspected by remember { mutableStateOf<PartnerReservation?>(null) }; var success by remember { mutableStateOf(false) }
    var scannerFailed by remember { mutableStateOf(false) }; var scannerOpening by remember { mutableStateOf(false) }; var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    fun checkToken(value: String) {
        if (operation.busy) return
        inspected = null; token = null; success = false
        scope.launch { operation.run {
            require(value.isNotBlank() && value.length in 20..512 && !value.contains("://"))
            val result = repository.inspect(value)
            require(company == null || result.companyId == company.companyId)
            inspected = result; token = value; requestId = UUID.randomUUID().toString()
        } }
    }
    PartnerSensitiveWindowEffect()
    BackHandler(onBack = onBack)
    AeroPage(title = stringResource(R.string.partnerPortal_scan), ownerShortcutEnabled = false, compactTitleWithBack = true) {
        company?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(R.string.partnerPortal_inspectHint))
        Button(onClick = {
            if (!operation.busy) {
                scannerFailed = false; scannerOpening = true; inspected = null; token = null; success = false
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
                try { GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { scanned -> scannerOpening = false; scanned.rawValue?.let(::checkToken) }
                    .addOnCanceledListener { scannerOpening = false }
                    .addOnFailureListener { scannerOpening = false; scannerFailed = true }
                } catch (_: Exception) { scannerOpening = false; scannerFailed = true }
            }
        }, enabled = !operation.busy && !scannerOpening, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_scan)) }
        if (scannerFailed) Text(stringResource(R.string.partnerPortal_scannerUnavailable), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.partnerPortal_enterToken), style = MaterialTheme.typography.titleSmall)
        PortalField(input, R.string.partnerPortal_tokenInput) { input = it.take(512); inspected = null; token = null; success = false }
        OutlinedButton(onClick = { checkToken(input.trim()) }, enabled = !operation.busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_inspect)) }
        PortalOperationFeedback(operation)
        if (operation.error != null) Text(stringResource(R.string.partnerPortal_notConfirmed), color = MaterialTheme.colorScheme.error)
        inspected?.let { reservation ->
            Text(reservation.title, style = MaterialTheme.typography.headlineSmall)
            Text(reservation.companyName); Text(reservation.terms)
            Text(stringResource(R.string.partnerPortal_reference, reservation.reservationId.takeLast(10)))
            if (success) Text(stringResource(R.string.partnerPortal_redeemSuccess), color = MaterialTheme.colorScheme.primary)
            else Button(onClick = { val checkedToken = token ?: return@Button
                scope.launch { operation.run { val redeemed = repository.redeem(checkedToken, requestId); check(redeemed.status == "redeemed"); inspected = redeemed; success = true; if (success) { token = null; input = "" } } }
            }, enabled = !operation.busy && token != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_confirmRedeem)) }
        }
    }
}
