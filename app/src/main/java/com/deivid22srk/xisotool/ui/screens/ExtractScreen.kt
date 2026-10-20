package com.deivid22srk.xisotool.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deivid22srk.xisotool.nativebridge.XisoNative
import com.deivid22srk.xisotool.ui.components.PathCard
import com.deivid22srk.xisotool.ui.components.SectionTitle
import com.deivid22srk.xisotool.util.Paths
import com.deivid22srk.xisotool.util.StorageAccess
import com.deivid22srk.xisotool.util.Toasts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen(
    isBusy: Boolean,
    onStart: (isoPath: String, outDir: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var isoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var destDir by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerRequest by remember { mutableStateOf<PickerRequest?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var isoValid by remember(isoPath) { mutableStateOf<Boolean?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (pendingStart && isoPath != null && destDir != null) {
            onStart(isoPath!!, File(destDir!!, File(isoPath!!).nameWithoutExtension).absolutePath)
        }
        pendingStart = false
    }

    LaunchedEffect(isoPath) {
        isoValid = null
        val path = isoPath ?: return@LaunchedEffect
        isoValid = withContext(Dispatchers.IO) {
            runCatching { XisoNative.checkXiso(path) }.getOrNull()
        }
    }

    val currentPicker = pickerRequest
    if (currentPicker != null) {
        FilePickerScreen(
            request = currentPicker,
            onPicked = { path ->
                when (currentPicker.mode) {
                    PickMode.ISO_FILE -> isoPath = path
                    PickMode.FOLDER -> destDir = path
                    PickMode.SAVE_ISO -> Unit
                }
                pickerRequest = null
            },
            onClose = { pickerRequest = null },
        )
        return
    }

    fun startOperation() {
        val iso = isoPath ?: return
        val dest = destDir ?: return
        if (!StorageAccess.hasNotificationPermission(context)) {
            pendingStart = true
            notificationPermissionLauncher.launch(StorageAccess.notificationPermission())
            return
        }
        onStart(iso, File(dest, File(iso).nameWithoutExtension).absolutePath)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Extrair ISO") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Voltar",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Escolha a imagem XISO e a pasta onde os arquivos do jogo serão gravados.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Imagem de origem")
            PathCard(
                title = isoPath?.substringAfterLast('/') ?: "Selecionar ISO",
                subtitle = isoPath ?: "Toque para escolher um arquivo .iso ou .xiso",
                icon = Icons.Rounded.Album,
                isValid = isoValid,
                onClick = { pickerRequest = PickerRequest(PickMode.ISO_FILE, "Escolher imagem ISO") },
            )
            if (isoValid == false) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Este arquivo não parece ser uma imagem XISO válida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            SectionTitle("Pasta de destino")
            PathCard(
                title = destDir?.substringAfterLast('/') ?: "Selecionar pasta",
                subtitle = destDir
                    ?: "Os arquivos serão extraídos para uma subpasta com o nome da ISO. Padrão: Downloads",
                icon = Icons.Rounded.FolderOpen,
                isValid = if (destDir != null) true else null,
                onClick = {
                    pickerRequest = PickerRequest(
                        mode = PickMode.FOLDER,
                        title = "Escolher pasta de destino",
                        initialDir = destDir ?: Paths.downloads(),
                    )
                },
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = ::startOperation,
                enabled = isoPath != null && destDir != null && !isBusy,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (isBusy) "Operação em andamento" else "Iniciar extração",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (isBusy) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = "Já existe uma operação em andamento. Aguarde a conclusão ou cancele na tela de progresso.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
