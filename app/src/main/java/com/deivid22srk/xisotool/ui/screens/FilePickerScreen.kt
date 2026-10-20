package com.deivid22srk.xisotool.ui.screens

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deivid22srk.xisotool.util.Formats
import com.deivid22srk.xisotool.util.Paths
import com.deivid22srk.xisotool.util.StorageAccess
import com.deivid22srk.xisotool.util.Texts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PickMode { ISO_FILE, FOLDER, SAVE_ISO }

data class PickerRequest(
    val mode: PickMode,
    val title: String,
    val initialDir: String? = null,
    val suggestedName: String? = null,
    val tag: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    request: PickerRequest,
    onPicked: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var currentDir by remember {
        mutableStateOf(
            File(request.initialDir ?: Paths.storageRoot()).takeIf { it.isDirectory } ?: File(Paths.storageRoot()),
        )
    }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var unreadable by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var fileName by remember { mutableStateOf(request.suggestedName ?: "") }

    LaunchedEffect(currentDir, refresh) {
        loading = true
        val dir = currentDir
        val result = withContext(Dispatchers.IO) {
            val children = dir.listFiles()
            if (children == null) {
                Pair(emptyList<File>(), !dir.canRead())
            } else {
                val filtered = children
                    .filter { entry ->
                        when (request.mode) {
                            PickMode.ISO_FILE -> true
                            else -> entry.isDirectory
                        }
                    }
                    .filter { entry ->
                        entry.isDirectory ||
                            request.mode != PickMode.ISO_FILE ||
                            entry.extension.lowercase() in setOf("iso", "xiso")
                    }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                Pair(filtered, false)
            }
        }
        entries = result.first
        unreadable = result.second
        loading = false
    }

    fun goUp() {
        currentDir.parentFile?.takeIf { it.isDirectory }?.let { currentDir = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(request.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Fechar")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Barra de caminho atual + navegar para cima
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    IconButton(
                        onClick = ::goUp,
                        enabled = currentDir.parentFile != null,
                        content = {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = "Pasta acima",
                            )
                        },
                    )
                }
            }

            // Atalhos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { currentDir = File(Paths.storageRoot()) },
                    label = { Text("Armazenamento") },
                )
                FilterChip(
                    selected = false,
                    onClick = { currentDir = File(Paths.downloads()) },
                    label = { Text("Downloads") },
                )
                FilterChip(
                    selected = false,
                    onClick = { currentDir = File(Paths.documents()) },
                    label = { Text("Documentos") },
                )
            }

            HorizontalDivider()

            when {
                !StorageAccess.hasFullAccess(context) -> {
                    EmptyState(
                        icon = Icons.Rounded.Warning,
                        message = "Sem acesso aos arquivos. Conceda o acesso na tela anterior para navegar pelas pastas.",
                    )
                }

                unreadable -> {
                    EmptyState(
                        icon = Icons.Rounded.Warning,
                        message = "Não foi possível ler esta pasta. Verifique o acesso ao armazenamento.",
                    )
                }

                loading -> {
                    EmptyState(icon = Icons.Rounded.Folder, message = "Carregando...")
                }

                entries.isEmpty() && request.mode == PickMode.ISO_FILE -> {
                    EmptyState(
                        icon = Icons.Rounded.Album,
                        message = "Nenhum arquivo ISO encontrado nesta pasta.",
                    )
                }

                entries.isEmpty() -> {
                    EmptyState(icon = Icons.Rounded.Folder, message = "Pasta vazia.")
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(entries, key = { it.absolutePath }) { entry ->
                            val isDir = entry.isDirectory
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            currentDir = entry
                                            refresh++
                                        } else {
                                            onPicked(entry.absolutePath)
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = when {
                                        isDir -> Icons.Rounded.Folder
                                        entry.extension.lowercase() in setOf("iso", "xiso") -> Icons.Rounded.Album
                                        else -> Icons.Rounded.InsertDriveFile
                                    },
                                    contentDescription = if (isDir) "Pasta" else "Arquivo",
                                    tint = if (isDir) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 14.dp),
                                )
                                if (!isDir) {
                                    Text(
                                        text = Formats.size(context, entry.length()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            when (request.mode) {
                PickMode.ISO_FILE -> {
                    Text(
                        text = "Navegue até o arquivo .iso e toque nele para selecionar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                PickMode.FOLDER -> {
                    Button(
                        onClick = { onPicked(currentDir.absolutePath) },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(52.dp),
                    ) {
                        Text("Usar esta pasta", style = MaterialTheme.typography.titleMedium)
                    }
                }

                PickMode.SAVE_ISO -> {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = Texts.sanitizeFileName(it) },
                            label = { Text("Nome do arquivo ISO") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val name = fileName.trim().ifEmpty { "imagem.iso" }
                                val finalName = if (name.endsWith(".iso", ignoreCase = true)) name else "$name.iso"
                                onPicked(File(currentDir, finalName).absolutePath)
                            },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text("Salvar aqui", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
