package com.kungsbackacarcommunity.app.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * The one-time first-login welcome flow, shown ONCE after a user reaches the
 * Main experience for the first time (profile already created). A short,
 * skippable multi-step intro that ends by nudging the user to complete their
 * profile and add their first car.
 *
 * This is deliberately separate from — and lighter than — the mandatory
 * [com.kungsbackacarcommunity.app.onboarding.OnboardingScreen] profile-creation
 * gate: this flow collects nothing, only orients the new member.
 *
 * All navigation-away callbacks ([onSeeMembership], [onCompleteProfile],
 * [onAddCar]) and the finish/skip paths ([onFinish]) are expected to mark the
 * flow as seen in the caller, so it never re-appears for a returning user. The
 * shared "Aero" brand styling (frosted, rounded, tonally-elevated surfaces on a
 * plain page background) mirrors [com.kungsbackacarcommunity.app.shell.AeroPage].
 * Wrap in [KccTheme].
 */
@Composable
fun WelcomeScreen(
    onSeeMembership: () -> Unit,
    onCompleteProfile: () -> Unit,
    onAddCar: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(WelcomeStep.FIRST) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(KccSpacing.s6),
        ) {
            // Top chrome: step progress + a persistent Skip (marks the flow seen).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.welcome_progress,
                            WelcomeFlow.position(step),
                            WelcomeStep.COUNT,
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.welcome_skip))
                }
            }

            Spacer(modifier = Modifier.size(KccSpacing.s4))

            // Scrollable content card so long copy / small screens stay reachable.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
            ) {
                StepCard(step = step)

                // Step-specific CTAs to existing screens.
                when (step) {
                    WelcomeStep.Membership ->
                        Button(
                            onClick = onSeeMembership,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.welcome_seeMembership))
                        }

                    WelcomeStep.Profile -> {
                        OutlinedButton(
                            onClick = onCompleteProfile,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.welcome_completeProfile))
                        }
                        OutlinedButton(
                            onClick = onAddCar,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.welcome_addCar))
                        }
                    }

                    else -> Unit
                }
            }

            Spacer(modifier = Modifier.size(KccSpacing.s4))

            // Primary advance / finish action. The whole screen already applies
            // safeDrawingPadding() (which includes the navigation-bar inset), so
            // no extra navigationBarsPadding() here — that would apply the inset
            // twice and push the CTA up on gesture-nav devices.
            Button(
                onClick = { if (WelcomeFlow.isLast(step)) onFinish() else step = WelcomeFlow.next(step) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        stringResource(
                            if (WelcomeFlow.isLast(step)) {
                                R.string.welcome_getStarted
                            } else {
                                R.string.welcome_next
                            },
                        ),
                )
            }
        }
    }
}

/**
 * The frosted, rounded content surface for a single [step]: a tonally-elevated
 * icon disc, the step title and body. Mirrors the map-first home's floating
 * controls (Aero styling).
 */
@Composable
private fun StepCard(step: WelcomeStep) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(KccSpacing.s6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = step.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(KccSpacing.s8),
                )
            }
            Text(
                text = stringResource(step.titleRes()),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(step.bodyRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun WelcomeStep.icon(): ImageVector =
    when (this) {
        WelcomeStep.Welcome -> Icons.Filled.Celebration
        WelcomeStep.Map -> Icons.Filled.Map
        WelcomeStep.Membership -> Icons.Filled.WorkspacePremium
        WelcomeStep.Profile -> Icons.Filled.DirectionsCar
    }

private fun WelcomeStep.titleRes(): Int =
    when (this) {
        WelcomeStep.Welcome -> R.string.welcome_step1Title
        WelcomeStep.Map -> R.string.welcome_step2Title
        WelcomeStep.Membership -> R.string.welcome_step3Title
        WelcomeStep.Profile -> R.string.welcome_step4Title
    }

private fun WelcomeStep.bodyRes(): Int =
    when (this) {
        WelcomeStep.Welcome -> R.string.welcome_step1Body
        WelcomeStep.Map -> R.string.welcome_step2Body
        WelcomeStep.Membership -> R.string.welcome_step3Body
        WelcomeStep.Profile -> R.string.welcome_step4Body
    }

@Preview(name = "Welcome", showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    KccTheme {
        WelcomeScreen(
            onSeeMembership = {},
            onCompleteProfile = {},
            onAddCar = {},
            onFinish = {},
        )
    }
}
