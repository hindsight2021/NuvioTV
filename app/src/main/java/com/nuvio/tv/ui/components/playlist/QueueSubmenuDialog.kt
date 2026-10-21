package com.nuvio.tv.ui.components.playlist

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
 * Dialog presenting queue-related actions for a playlist item.
 *
 * Displays a vertical stack of actions: Play Next, Add to Queue, optionally
 * Clear Queue (when the queue is non-empty), and Cancel. Focus is automatically
 * requested on the primary "Play Next" action when the dialog appears.
 *
 * @param title Dialog title.
 * @param queueSize Current number of items in the queue; controls visibility of the clear action.
 * @param onPlayNext Invoked when the user chooses to play the item next.
 * @param onAddToQueue Invoked when the user chooses to append the item to the queue.
 * @param onClearQueue Invoked when the user chooses to clear the queue.
 * @param onDismiss Invoked when the user cancels or dismisses the dialog.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QueueSubmenuDialog(
    title: String,
    queueSize: Int,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    val playNextFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { playNextFocusRequester.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        width = 440.dp,
        title = title,
        subtitle = "Playlist Queue"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QueueActionButton(
                text = "Play Next",
                onClick = onPlayNext,
                modifier = Modifier.focusRequester(playNextFocusRequester)
            )

            QueueActionButton(
                text = "Add to Queue",
                onClick = onAddToQueue
            )

            if (queueSize > 0) {
                val clearLabel = if (queueSize == 1) "Clear Queue (1 item)" else "Clear Queue ($queueSize items)"
                QueueActionButton(
                    text = clearLabel,
                    onClick = onClearQueue
                )
            }

            QueueActionButton(
                text = "Cancel",
                onClick = onDismiss
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
