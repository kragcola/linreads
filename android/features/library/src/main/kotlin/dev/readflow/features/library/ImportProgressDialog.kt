package dev.readflow.features.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImportProgressDialog(found: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 禁止点击外部关闭 */ },
        title = { Text("扫描文件夹") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                Text("已发现 $found 本书…")
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}
