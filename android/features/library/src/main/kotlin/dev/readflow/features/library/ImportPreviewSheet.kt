package dev.readflow.features.library

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.readflow.extensions.api.ScannedBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewSheet(
    books: List<ScannedBook>,
    onImport: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(books) { mutableStateMapOf<Uri, Boolean>().also { m -> books.forEach { m[it.uri] = true } } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("发现 ${books.size} 本书", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp))

            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
                items(books, key = { it.uri.toString() }) { book ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = selected[book.uri] == true,
                            onCheckedChange = { selected[book.uri] = it },
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(book.name.substringBeforeLast('.'), maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(book.format.name, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { books.forEach { selected[it.uri] = true } }, Modifier.weight(1f)) {
                    Text("全选")
                }
                TextButton(onClick = { books.forEach { selected[it.uri] = false } }, Modifier.weight(1f)) {
                    Text("取消全选")
                }
                val chosen = selected.filterValues { it }.keys.toList()
                Button(
                    onClick = { onImport(chosen); onDismiss() },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("导入(${chosen.size})") }
            }
        }
    }
}
