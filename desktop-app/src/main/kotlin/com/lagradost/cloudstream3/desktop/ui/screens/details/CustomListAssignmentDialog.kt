package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopCustomList

@Composable
fun CustomListAssignmentDialog(
    bookmark: DesktopBookmark,
    lists: List<DesktopCustomList>,
    selectedListIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to lists") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(bookmark.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                if (lists.isEmpty()) {
                    Text("Create a list in Library to organize this title.")
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        lists.forEach { list ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = list.id in selectedListIds,
                                    onCheckedChange = { checked -> onToggle(list.id, checked) },
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(list.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
