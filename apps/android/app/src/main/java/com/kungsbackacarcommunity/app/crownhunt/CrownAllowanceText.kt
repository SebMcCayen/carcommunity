package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CrownAllowanceText(allowance: CrownAllowance?) {
    allowance ?: return
    val reset = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Europe/Stockholm")).format(allowance.resetsAt)
    Text(stringResource(R.string.crownHunt_allowanceRemaining, allowance.remaining, allowance.cap, reset))
}
