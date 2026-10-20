package com.deivid22srk.xisotool.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.deivid22srk.xisotool.ui.components.PathCard
import com.deivid22srk.xisotool.ui.components.SectionTitle
import com.deivid22srk.xisotool.util.Paths
import com.deivid22srk.xisotool.util.StorageAccess
import com.deivid22srk.xisotool.util.Texts
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    isBusy: Boolean,
    onStart: (srcDir: String, outIsoPath: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var srcDir by rememberSaveable { mutableStateOf<String?>(null) }
    var destDir by rememberSaveable { mutableStateOf<String?>(null) }
    var isoName by rememberSaveable { mutableStateOf("") }
    var pickerRequest by remember { mutableStateOf<PickerRequest?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var hasXbe by remember(srcDir) { mutableStateOf<Boolean?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (pendingStart && srcDir != null && destDir != null && isoName.isNotBlank()) {
            onStart(srcDir!!, File(destDir!!, isoName).absolutePath)
        }
        pendingStart = false
    }

    LaunchedEffect(srcDir) {
        hasXbe = null
        val dir = srcDir ?: return@LaunchedEffect
        hasXbe = File(dir, "default.xbe").isFile
        if (isoName.isBlank()) {
            isoName = Texts.sanitizeFileName(File(dir).name) + ".iso"
        }
    }

    val currentPicker = pickerRequest
    if (currentPicker != null) {
        FilePickerScreen(
            request = currentPicker,
            onPicked = { path ->
                when (currentPicker.mode) {
                    PickMode.FOLDER -> if (currentPicker.tag == "source") srcDir = path else destDir = path
                    else -> Unit
                }
                pickerRequest = null
            },
            onClose = { pickerRequest = null },
        )
        return
    }

    fun startOperation() {
        val source = srcDir ?: return
        val dest = destDir ?: return
        val name = isoName.trim().ifBlank { return }
        if (!StorageAccess.hasNotificationPermission(context)) {
            pendingStart = true
            notificationPermissionLauncher.launch(StorageAccess.notificationPermission())
            return
        }
        onStart(source, File(dest, name).absolutePath)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Criar ISO") },
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
                text = "Escolha a pasta do jogo (com default.xbe na raiz) e onde a imagem XISO será gravada.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Pasta do jogo")
            PathCard(
                title = srcDir?.substringAfterLast('/') ?: "Selecionar pasta do jogo",
                subtitle = srcDir ?: "Deve conter default.xbe na raiz",
                icon = Icons.Rounded.Folder,
                isValid = hasXbe,
                onClick = {
                    pickerRequest = PickerRequest(
                        mode = PickMode.FOLDER,
                        title = "Escolher pasta do jogo",
                        initialDir = srcDir ?: Paths.storageRoot(),
                        tag = "source",
                    )
                },
            )
            if (hasXbe == false) {
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
                        text = "default.xbe não encontrado na raiz desta pasta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            SectionTitle("Local e nome da ISO")
            PathCard(
                title = destDir?.substringAfterLast('/') ?: "Selecionar pasta de destino",
                subtitle = destDir ?: "A imagem XISO será gravada nesta pasta. Padrão: Documentos",
                icon = Icons.Rounded.FolderOpen,
                isValid = if (destDir != null) true else null,
                onClick = {
                    pickerRequest = PickerRequest(
                        mode = PickMode.FOLDER,
                        title = "Escolher pasta de destino",
                        initialDir = destDir ?: Paths.documents(),
                        tag = "dest",
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = isoName,
                onValueChange = { isoName = Texts.sanitizeFileName(it) },
                label = { Text("Nome do arquivo ISO") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = ::startOperation,
                enabled = srcDir != null && hasXbe == true && destDir != null && isoName.isNotBlank() && !isBusy,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (isBusy) "Operação em andamento" else "Criar ISO",
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
