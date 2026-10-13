@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File

/**
 * TV-optimized folder picker dialog.
 * Allows D-pad navigation through mounted storage directories (e.g. Shield NAS mounts at /storage/...)
 * and selecting a specific folder for local Movies or TV Shows.
 */
@Composable
fun StorageFolderPickerDialog(
    title: String,
    initialPath: String? = null,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    val startDir = remember(initialPath) {
        val candidate = initialPath?.let { File(it) }
        when {
            candidate != null && candidate.exists() && candidate.isDirectory && candidate.canRead() -> candidate
            File("/storage").exists() && File("/storage").isDirectory -> File("/storage")
            else -> File("/")
        }
    }

    var currentDirectory by remember { mutableStateOf(startDir) }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { initialFocus.requestFocus() }
    }

    val subDirs = remember(currentDirectory) {
        runCatching {
            currentDirectory.listFiles { f -> f.isDirectory && !f.isHidden && f.canRead() }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    val parentFile = remember(currentDirectory) {
        currentDirectory.parentFile?.takeIf { it.canRead() }
    }

    val listState = rememberLazyListState()

    // Scroll list to top whenever current directory changes
    LaunchedEffect(currentDirectory) {
        listState.scrollToItem(0)
    }

    NuvioDialog(
        title = title,
        subtitle = "Browse mounted Shield folders and network shares",
        width = 680.dp,
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Display currently active path
            Text(
                text = currentDirectory.absolutePath,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = NuvioTheme.colors.Primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Button to confirm selection of the current folder
            Button(
                onClick = { onFolderSelected(currentDirectory.absolutePath) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(initialFocus),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.FocusBackground,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Select This Folder",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // Directory browser list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (parentFile != null) {
                    item(key = "__parent__") {
                        Button(
                            onClick = { currentDirectory = parentFile },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📁 .. (Go Up)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                if (subDirs.isEmpty()) {
                    item(key = "__empty__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No subfolders found in this directory",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = subDirs,
                        key = { _, dir -> dir.absolutePath }
                    ) { _, dir ->
                        Button(
                            onClick = { currentDirectory = dir },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = NuvioTheme.colors.TextSecondary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
