package com.nuvio.tv.ui.components.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Dialog for managing an item's presence in the Trakt and Simkl watchlists.
 *
 * Presents context-aware actions based on which services are connected and the
 * item's current membership in each watchlist. Focus is automatically requested
 * on the primary action button when the dialog is shown.
 *
 * @param title The display title of the media item.
 * @param isTraktConnected Whether the user has a connected Trakt account.
 * @param isSimklConnected Whether the user has a connected Simkl account.
 * @param isInTrakt Whether the item is currently in the Trakt watchlist.
 * @param isInSimkl Whether the item is currently in the Simkl watchlist.
 * @param isPending Whether an action is currently in-flight (disables buttons).
 * @param onToggleTrakt Invoked to add/remove the item from the Trakt watchlist.
 * @param onToggleSimkl Invoked to add/remove the item from the Simkl watchlist.
 * @param onAddBoth Invoked to add the item to both watchlists.
 * @param onRemoveBoth Invoked to remove the item from both watchlists.
 * @param onDismiss Invoked to close the dialog.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TraktSimklWatchlistDialog(
    title: String,
    isTraktConnected: Boolean,
    isSimklConnected: Boolean,
    isInTrakt: Boolean,
    isInSimkl: Boolean,
    isPending: Boolean = false,
    onToggleTrakt: () -> Unit,
    onToggleSimkl: () -> Unit,
    onAddBoth: () -> Unit,
    onRemoveBoth: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { primaryFocusRequester.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = "Trakt & Simkl Watchlist",
        width = 440.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                !isTraktConnected && !isSimklConnected -> {
                    Text(
                        text = "Connect Trakt or Simkl in Settings to manage your watchlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    DialogActionButton(
                        label = "Close",
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    )
                }

                else -> {
                    var primaryFocusAssigned = false

                    if (isTraktConnected) {
                        val isPrimary = !primaryFocusAssigned
                        primaryFocusAssigned = true
                        DialogActionButton(
                            label = if (isInTrakt) "✓ Remove from Trakt Watchlist"
                            else "+ Add to Trakt Watchlist",
                            onClick = onToggleTrakt,
                            enabled = !isPending,
                            modifier = if (isPrimary) Modifier.focusRequester(primaryFocusRequester)
                            else Modifier
                        )
                    }

                    if (isSimklConnected) {
                        val isPrimary = !primaryFocusAssigned
                        primaryFocusAssigned = true
                        DialogActionButton(
                            label = if (isInSimkl) "✓ Remove from Simkl Watchlist"
                            else "+ Add to Simkl Watchlist",
                            onClick = onToggleSimkl,
                            enabled = !isPending,
                            modifier = if (isPrimary) Modifier.focusRequester(primaryFocusRequester)
                            else Modifier
                        )
                    }

                    if (isTraktConnected && isSimklConnected) {
                        val bothIn = isInTrakt && isInSimkl
                        val bothOut = !isInTrakt && !isInSimkl

                        when {
                            bothIn -> {
                                DialogActionButton(
                                    label = "🗑 Remove from Both",
                                    onClick = onRemoveBoth,
                                    enabled = !isPending
                                )
                            }

                            bothOut -> {
                                DialogActionButton(
                                    label = "➕ Add to Both",
                                    onClick = onAddBoth,
                                    enabled = !isPending
                                )
                            }

                            else -> {
                                DialogActionButton(
                                    label = "➕ Add to Both",
                                    onClick = onAddBoth,
                                    enabled = !isPending
                                )
                                DialogActionButton(
                                    label = "🗑 Remove from Both",
                                    onClick = onRemoveBoth,
                                    enabled = !isPending
                                )
                            }
                        }
                    }

                    DialogActionButton(
                        label = "Cancel",
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
