package com.kungsbackacarcommunity.app.partners

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccButton
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The same menu entry shows current application status or the approved business workspace. */
@Composable
fun PartnerApplicationRoute(coordinator: PartnerApplicationCoordinator?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) { FirebasePartnerPortalRepository.createIfAvailable(context) }
    val operation = remember { PortalOperation() }
    var workspace by remember { mutableStateOf<PartnerWorkspace?>(null) }
    var editing by remember { mutableStateOf(false) }
    val status by (coordinator?.status ?: flowOf(PartnerApplicationStatus.Idle)).collectAsState(initial = PartnerApplicationStatus.Idle)
    LaunchedEffect(repository, status) {
        if (status == PartnerApplicationStatus.Done && com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()) {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PartnerApplicationDraftStore.forContext(context).clear(uid) }
            }
        }
        if (status != PartnerApplicationStatus.Submitting) operation.run { workspace = repository?.workspace() }
    }
    val application = workspace?.application
    if (repository != null && workspace?.companies?.isNotEmpty() == true && !editing && status != PartnerApplicationStatus.Done) {
        PartnerWorkspaceRoute(repository, onBack); return
    }
    if (operation.busy || operation.error != null) {
        AeroPage(title = stringResource(R.string.partnerPortal_application), ownerShortcutEnabled = false, compactTitleWithBack = true) {
            PortalOperationFeedback(operation)
            if (!operation.busy) KccButton(onClick = { scope.launch { operation.run { workspace = repository?.workspace() } } }) { Text(stringResource(R.string.partnerPortal_retry)) }
        }; return
    }
    if (application != null && !editing && status != PartnerApplicationStatus.Done) {
        AeroPage(title = stringResource(R.string.partnerPortal_application), ownerShortcutEnabled = false, compactTitleWithBack = true) {
            Text(application.fields.text("companyName")); PortalStatus(application.status)
            application.reviewReason?.let { Text(it) }
            if (application.status == "changes_requested") KccButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.partnerPortal_resubmit)) }
        }; return
    }
    BackHandler(enabled = editing) { editing = false }
    PartnerApplicationScreen(status, onSubmit = { input -> coordinator?.let { c -> scope.launch { c.submit(input) } } },
        onBack = { coordinator?.reset(); onBack() }, initial = if (editing) application else null)
}
