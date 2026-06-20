package dev.readflow.features.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookBundle
import dev.readflow.core.ui.BookCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleDetailSheet(
    bundle: BookBundle,
    onOpenBook: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(
                text = bundle.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(bundle.books, key = { it.id }) { book ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBook(book.id); onDismiss() }
                            .padding(vertical = 8.dp),
                    ) {
                        BookCover(
                            book = book,
                            modifier = Modifier.size(width = 44.dp, height = 60.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(book.author, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
