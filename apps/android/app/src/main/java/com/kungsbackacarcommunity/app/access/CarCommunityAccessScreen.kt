package com.kungsbackacarcommunity.app.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.account.AccountDeletionCoordinator
import com.kungsbackacarcommunity.app.account.AccountDeletionRoute
import com.kungsbackacarcommunity.app.profile.AccountWarningsSection

@Composable
fun CarCommunityAccessScreen(uid: String, state: CarCommunityGateState, onRetry: () -> Unit, onSignOut: () -> Unit, deletion: AccountDeletionCoordinator?) {
    var deleting by remember(uid) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    if (deleting) { AccountDeletionRoute(deletion, onDeleted = onSignOut, onBack = { deleting = false }); return }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state == CarCommunityGateState.Loading) {
            CircularProgressIndicator()
            Text(stringResource(R.string.carCommunityAccess_checking))
        } else {
            val status = (state as? CarCommunityGateState.Ready)?.status
            Text(stringResource(if (status?.blacklisted == true) R.string.carCommunityAccess_blacklistedTitle else R.string.carCommunityAccess_suspendedTitle))
            Text(stringResource(if (state == CarCommunityGateState.Failed) R.string.carCommunityAccess_error else R.string.carCommunityAccess_body))
            status?.reason?.let { Text(it) }
            AccountWarningsSection(uid)
            Button(onClick = onRetry) { Text(stringResource(R.string.carCommunityAccess_retry)) }
            TextButton(onClick = { uriHandler.openUri("https://github.com/SebMcCayen/carcommunity/issues") }) { Text(stringResource(R.string.accountStatus_supportLink)) }
            if (deletion != null) TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.settings_deleteAccount)) }
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.auth_signOut)) }
        }
    }
}
