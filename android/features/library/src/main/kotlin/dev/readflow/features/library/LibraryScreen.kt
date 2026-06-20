package dev.readflow.features.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.readflow.core.model.BookBundle
import dev.readflow.core.ui.BookGrid
import dev.readflow.core.ui.EmptyState
import dev.readflow.core.ui.PaperSurface
import org.koin.androidx.compose.koinViewModel

private val SUPPORTED_MIMES = arrayOf("text/plain", "application/epub+zip", "application/pdf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val context = LocalContext.current

    var openedBundle by remember { mutableStateOf<BookBundle?>(null) }

    LaunchedEffect(Unit) {
        viewModel.openBook.collect { bookId -> onOpenBook(bookId) }
    }
    LaunchedEffect(Unit) {
        viewModel.openBundle.collect { bundle -> openedBundle = bundle }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBook(it) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.scanFolder(context, it) }
    }

    var showAddMenu by remember { mutableStateOf(false) }

    PaperSurface {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("书架", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        Box {
                            IconButton(onClick = { showAddMenu = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = "导入书籍")
                            }
                            DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("选择文件") },
                                    onClick = { showAddMenu = false; fileLauncher.launch(SUPPORTED_MIMES) },
                                )
                                DropdownMenuItem(
                                    text = { Text("扫描文件夹") },
                                    onClick = { showAddMenu = false; folderLauncher.launch(null) },
                                )
                            }
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                    state.error != null -> Text("加载失败：${state.error}", color = MaterialTheme.colorScheme.onBackground)
                    state.items.isEmpty() -> EmptyState(
                        onConnectCalibre = onSettings,
                        onImportLocal = { fileLauncher.launch(SUPPORTED_MIMES) },
                    )
                    else -> BookGrid(
                        items = state.items,
                        onItemClick = viewModel::onItemClick,
                        onDelete = viewModel::deleteBook,
                        onRename = viewModel::renameBook,
                        onMoveToGroup = viewModel::moveToGroup,
                        onReorder = viewModel::reorder,
                    )
                }
            }
        }

        // 扫描进度 dialog（不可外部关闭）
        if (scanProgress?.scanning == true) {
            ImportProgressDialog(
                found = scanProgress!!.found,
                onCancel = viewModel::cancelScan,
            )
        }

        // 扫描完成 → 展示预览 sheet
        if (scanProgress?.scanning == false && scanProgress!!.books.isNotEmpty()) {
            ImportPreviewSheet(
                books = scanProgress!!.books,
                onImport = { viewModel.importFromFolder(it) },
                onDismiss = viewModel::clearScan,
            )
        }

        // 分组详情 sheet
        openedBundle?.let { bundle ->
            BundleDetailSheet(
                bundle = bundle,
                onOpenBook = onOpenBook,
                onDismiss = { openedBundle = null },
            )
        }
    }
}
