package com.kungsbackacarcommunity.app.usersearch

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl

/**
 * The "search for a person" field.
 *
 * Rendered as its own card above the add-by-nickname form on the Friends
 * surface. The rows it produces are emitted separately (see
 * [memberSearchResultRows] usage in FriendsScreen) so a long result list stays
 * lazily composed inside the page's LazyColumn instead of inflating twenty rows
 * inside one item.
 *
 * A trailing progress indicator, rather than replacing the list with a spinner,
 * is what keeps the previous suggestions readable while the next search runs.
 */
@Composable
fun MemberSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    state: UserSearchState,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.userSearch_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state is UserSearchState.Searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(KccSpacing.s5),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                // Search, not Send: this field never submits anything — results
                // arrive as you type — so offering a "send" key would promise an
                // action that does not exist.
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            )

            when (state) {
                UserSearchState.TooShort ->
                    HintText(stringResource(R.string.userSearch_keepTyping))

                UserSearchState.Empty ->
                    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                        HintText(stringResource(R.string.userSearch_noMatches))
                        // Explains the prefix-only limitation exactly where a
                        // fruitless search happens: without it, someone typing
                        // the MIDDLE of a nickname concludes the member is not
                        // on the app rather than that the search starts at the
                        // beginning of a name.
                        HintText(stringResource(R.string.userSearch_matchesFromStart))
                    }

                is UserSearchState.Failed ->
                    Text(
                        text = stringResource(state.error.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                is UserSearchState.Searching ->
                    // Only when there is nothing else to look at. Once previous
                    // rows are on screen the trailing spinner already says
                    // "refreshing", and a redundant line of text under the field
                    // just pushes those rows further from the keyboard.
                    if (state.previous.isEmpty()) {
                        HintText(stringResource(R.string.userSearch_searching))
                    }

                UserSearchState.Idle, is UserSearchState.Results -> Unit
            }
        }
    }
}

/**
 * One suggestion row. Tapping it opens that member's read-only profile, which is
 * where adding them as a friend, messaging or blocking lives — the search itself
 * deliberately carries no actions, so a row is never a button that does
 * something irreversible to a person you have only half-identified.
 */
@Composable
fun MemberSearchResultRow(
    member: MemberSearchResult,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // TalkBack reads this as "<name>, button — double tap to open profile".
    // Without the label a screen-reader user hears only a name and a generic
    // "double tap to activate", with no way to know that activating it navigates
    // to that member rather than, say, sending them a request.
    val openProfileLabel = stringResource(R.string.userSearch_openProfile)
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Announced as a button so screen readers expose the
                    // tap-to-open-profile affordance, which is otherwise
                    // invisible on a row of plain text.
                    .clickable(
                        role = Role.Button,
                        onClickLabel = openProfileLabel,
                        onClick = onOpenProfile,
                    )
                    .padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            MemberSearchAvatar(avatarPath = member.avatarPath)
            Text(
                text = member.displayName ?: stringResource(R.string.userSearch_unknownMember),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MemberSearchAvatar(avatarPath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(KccSpacing.s10)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(KccSpacing.s10),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KccSpacing.s6),
            )
        }
    }
}

/** The `userSearch.*` string for a mapped [UserSearchError]. */
@StringRes
internal fun UserSearchError.messageRes(): Int =
    when (this) {
        UserSearchError.SignedOut -> R.string.userSearch_errorSignedOut
        UserSearchError.NotMember -> R.string.friends_errorNotMember
        UserSearchError.RateLimited -> R.string.userSearch_errorRateLimited
        UserSearchError.Network -> R.string.friends_errorNetwork
        UserSearchError.Generic -> R.string.userSearch_error
    }
