package com.kungsbackacarcommunity.app.partners

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccButton as Button
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.shell.AeroPage

/** Four short steps; private review contacts are separate from published profile fields. */
@Composable
fun PartnerApplicationScreen(
    status: PartnerApplicationStatus,
    onSubmit: (PartnerApplicationInput) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initial: PartnerApplicationSummary? = null,
) {
    val context = LocalContext.current
    val uid = if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance().currentUser?.uid
    key(uid, initial?.applicationId) { PartnerApplicationContent(status, onSubmit, onBack, modifier, initial, uid) }
}

@Composable
private fun PartnerApplicationContent(status: PartnerApplicationStatus, onSubmit: (PartnerApplicationInput) -> Unit,
    onBack: () -> Unit, modifier: Modifier, initial: PartnerApplicationSummary?, uid: String?) {
    val context = LocalContext.current
    val draftStore = remember(context) { PartnerApplicationDraftStore.forContext(context) }
    var draft by remember(uid) { mutableStateOf<PartnerApplicationDraft?>(null) }
    var draftLoaded by remember(uid) { mutableStateOf(false) }
    LaunchedEffect(uid, status) {
        if (uid != null) withContext(Dispatchers.IO) {
            if (status == PartnerApplicationStatus.Done) draftStore.clear(uid)
            else if (!draftLoaded) draft = draftStore.read(uid)
        }
        draftLoaded = true
    }
    if (!draftLoaded) { androidx.compose.material3.CircularProgressIndicator(); return }
    val seed = initial?.fields.orEmpty()
    val draftForm = draft?.form
    var step by rememberSaveable { mutableIntStateOf(draft?.step ?: 0) }
    var companyName by rememberSaveable { mutableStateOf(draftForm?.companyName ?: seed.text("companyName")) }
    var category by rememberSaveable { mutableStateOf<PartnerCategory?>(draftForm?.category ?: seed.optionalText("category")?.let(PartnerCategory::fromWire)) }
    var organization by rememberSaveable { mutableStateOf(draftForm?.organizationNumber ?: seed.text("organizationNumber")) }
    var description by rememberSaveable { mutableStateOf(draftForm?.description ?: seed.text("description")) }
    var address by rememberSaveable { mutableStateOf(draftForm?.address ?: seed.text("address")) }
    var publicPhone by rememberSaveable { mutableStateOf(draftForm?.publicPhone ?: seed.text("publicPhone")) }
    var contactName by rememberSaveable { mutableStateOf(draftForm?.contactName ?: seed.text("contactName")) }
    var contactEmail by rememberSaveable { mutableStateOf(draftForm?.contactEmail ?: seed.text("contactEmail")) }
    var contactPhone by rememberSaveable { mutableStateOf(draftForm?.contactPhone ?: seed.text("contactPhone")) }
    var website by rememberSaveable { mutableStateOf(draftForm?.websiteUrl ?: seed.text("website")) }
    var message by rememberSaveable { mutableStateOf(draftForm?.message ?: seed.text("message")) }
    var selfService by rememberSaveable { mutableStateOf(draftForm?.selfServiceRequested ?: (seed["selfServiceRequested"] == true)) }
    var billboard by rememberSaveable { mutableStateOf(draftForm?.billboardRequested ?: (seed["billboardRequested"] == true)) }
    var placement by rememberSaveable { mutableStateOf(draftForm?.billboardPlacementRequest ?: seed.text("billboardPlacementRequest")) }
    var logo by remember { mutableStateOf<String?>(null) }
    var rights by rememberSaveable { mutableStateOf(false) }
    var logoBusy by remember { mutableStateOf(false) }
    var logoError by remember { mutableStateOf(false) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val picker = rememberImagePickLauncher(maxBytes = PartnerLogoEncoding.MAX_BYTES, onPickFailed = { logoError = true }) { picked ->
        if (picked != null) {
            logoBusy = true
            rights = false
            try { logo = PartnerLogoEncoding.encode(picked); logoError = false }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { logoError = true }
            finally { logoBusy = false }
        }
    }
    val form = PartnerApplicationForm(companyName, category, contactName, contactEmail, contactPhone, website, message,
        organization, description, address, publicPhone, selfService, billboard, placement, logo, rights)
    val scope = rememberCoroutineScope()
    var draftSaved by remember { mutableStateOf(false) }
    val busy = status == PartnerApplicationStatus.Submitting || logoBusy
    BackHandler(enabled = step > 0 && !busy && status != PartnerApplicationStatus.Done) { step--; showError = false }
    AeroPage(ownerShortcutEnabled = false, title = stringResource(R.string.partnerPortal_apply), compactTitleWithBack = true,
        modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp),
        contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars)) {
        if (status == PartnerApplicationStatus.Done) {
            Text(stringResource(R.string.partners_submitSuccess), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_close)) }
            return@AeroPage
        }
        val steps = listOf(R.string.partnerPortal_company, R.string.partnerPortal_contactAccount, R.string.partnerPortal_visibility, R.string.partnerPortal_review)
        LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
        Text("${step + 1}/4 · ${stringResource(steps[step])}", style = MaterialTheme.typography.titleLarge)
        when (step) {
            0 -> {
                PortalField(companyName, R.string.partners_companyNameLabel) { companyName = it.take(150) }
                PortalField(organization, R.string.partnerPortal_organization) { organization = it.take(32) }
                PortalField(description, R.string.partnerPortal_description, multiline = true) { description = it.take(2000) }
                PortalField(address, R.string.partnerPortal_address) { address = it.take(300) }
                PortalField(publicPhone, R.string.partnerPortal_publicPhone, KeyboardType.Phone) { publicPhone = it.take(30) }
                PortalField(website, R.string.partners_websiteLabel, KeyboardType.Uri) { website = it.take(500) }
                Text(stringResource(R.string.partners_categoryLabel), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    PartnerCategory.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(category == option, role = Role.RadioButton, onClick = { category = option }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = category == option, onClick = null)
                            Text(stringResource(option.labelRes()))
                        }
                    }
                }
            }
            1 -> {
                val account = if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance().currentUser
                (account?.email ?: account?.displayName)?.let { Text(stringResource(R.string.partnerPortal_signedInAccount, it), style = MaterialTheme.typography.titleSmall) }
                Text(stringResource(R.string.partnerPortal_privateContact), style = MaterialTheme.typography.bodyMedium)
                PortalField(contactName, R.string.partners_contactNameLabel) { contactName = it.take(120) }
                PortalField(contactEmail, R.string.partners_contactEmailLabel, KeyboardType.Email) { contactEmail = it.take(254) }
                PortalField(contactPhone, R.string.partners_contactPhoneLabel, KeyboardType.Phone) { contactPhone = it.take(30) }
                PortalToggle(selfService, R.string.partnerPortal_selfService) { selfService = it }
                Text(stringResource(R.string.partnerPortal_selfServiceHint), style = MaterialTheme.typography.bodyMedium)
            }
            2 -> {
                Text(stringResource(R.string.partnerPortal_logoHint))
                OutlinedButton(onClick = { picker.pickImage() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (logo != null) R.string.partnerPortal_logoReady else R.string.partnerPortal_logo))
                }
                (logo ?: initial?.fields?.optionalText("logoPreviewBase64"))?.let { PartnerLogoPreview(it) }
                if (logo != null) PortalToggle(rights, R.string.partnerPortal_logoRights) { rights = it }
                if (logoError) Text(stringResource(R.string.partnerPortal_logoError), color = MaterialTheme.colorScheme.error)
                PortalToggle(billboard, R.string.partnerPortal_billboardInterest) { billboard = it }
                if (billboard) {
                    Text(stringResource(R.string.partnerPortal_billboardHint))
                    PortalField(placement, R.string.partnerPortal_placement, multiline = true) { placement = it.take(1000) }
                }
                PortalField(message, R.string.partners_messageLabel, multiline = true) { message = it.take(2000) }
            }
            3 -> {
                Text(stringResource(R.string.partnerPortal_publicPreview), style = MaterialTheme.typography.titleMedium)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        (logo ?: initial?.fields?.optionalText("logoPreviewBase64"))?.let { PartnerLogoPreview(it) }
                        Text(companyName, style = MaterialTheme.typography.headlineSmall)
                        category?.let { Text(stringResource(it.labelRes()), color = MaterialTheme.colorScheme.primary) }
                        Text(description)
                        Text(address)
                        if (publicPhone.isNotBlank()) Text(publicPhone)
                        if (website.isNotBlank()) Text(PartnerApplications.normalizeWebsiteUrl(website).orEmpty())
                    }
                }
                Text(stringResource(R.string.partnerPortal_contactAccount), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.partnerPortal_privateContact))
                Text("${stringResource(R.string.partners_contactNameLabel)}: $contactName")
                Text("${stringResource(R.string.partners_contactEmailLabel)}: $contactEmail")
                if (contactPhone.isNotBlank()) Text("${stringResource(R.string.partners_contactPhoneLabel)}: $contactPhone")
                if (selfService) Text(stringResource(R.string.partnerPortal_selfService))
                if (billboard) Text(stringResource(R.string.partnerPortal_billboardInterest))
                if (logo != null) Text(stringResource(R.string.partnerPortal_logoReady))
                Text(stringResource(R.string.partnerPortal_privateContact))
                Text(stringResource(R.string.partnerPortal_noPayment))
            }
        }
        if (showError) Text(stringResource(R.string.partnerPortal_invalidFields), color = MaterialTheme.colorScheme.error)
        if (status is PartnerApplicationStatus.Failed) {
            Text(stringResource(when(status.reason) {
                PartnerApplicationFailureReason.INVALID_ARGUMENT -> R.string.partners_submitErrorInvalid
                PartnerApplicationFailureReason.ALREADY_EXISTS -> R.string.partners_submitErrorDuplicate
                PartnerApplicationFailureReason.UNKNOWN -> R.string.partnerPortal_error
            }), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = {
            val valid = when(step) {
                0 -> companyName.isNotBlank() && category != null && description.isNotBlank() && address.isNotBlank()
                1 -> PartnerApplications.validate(form) == null
                2 -> (logo == null || rights) && (!billboard || logo != null || initial?.fields?.optionalText("logoPreviewBase64") != null)
                else -> PartnerApplications.validate(form) == null && (logo == null || rights) && (!billboard || logo != null || initial?.fields?.optionalText("logoPreviewBase64") != null)
            }
            showError = !valid
            if (valid) { if (step < 3) step++ else PartnerApplications.toInput(form)?.let(onSubmit) }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.partnerPortal_working else if (step == 3) R.string.partners_submitButton else R.string.partnerPortal_next))
        }
        if (uid != null) {
            Text(stringResource(R.string.partnerPortal_localDraftHint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scope.launch {
                try { withContext(Dispatchers.IO) { draftStore.save(uid, PartnerApplicationDraft(form.copy(logoBase64 = null, logoRightsConfirmed = false), if (billboard) step.coerceAtMost(2) else step)) }; draftSaved = true }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { showError = true }
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_saveLocalDraft)) }
            if (draftSaved) Text(stringResource(R.string.partnerPortal_draftSaved))
        }
        if (step > 0) OutlinedButton(onClick = { step--; showError = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_previous)) }
    }
}

@Composable
internal fun PortalField(value: String, label: Int, keyboardType: KeyboardType = KeyboardType.Text, multiline: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = Modifier.fillMaxWidth())
}
@Composable
internal fun PortalToggle(checked: Boolean, label: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null); Text(stringResource(label), Modifier.weight(1f))
    }
}
