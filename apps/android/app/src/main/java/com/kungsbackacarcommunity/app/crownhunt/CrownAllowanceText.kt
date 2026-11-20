package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R

/** Secondary receipt snapshot; the raw reset timestamp is not a user action. */
@Composable
internal fun CrownAllowanceText(allowance: CrownAllowance?) {
    allowance ?: return
    Text(stringResource(R.string.crownHunt_allowanceRemaining, allowance.remaining, allowance.cap),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
