package com.deivid22srk.xisotool.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deivid22srk.xisotool.data.ErrorCodes
import com.deivid22srk.xisotool.data.OpType
import com.deivid22srk.xisotool.data.TaskState
import com.deivid22srk.xisotool.service.XisoForegroundService
import com.deivid22srk.xisotool.util.Texts
import com.deivid22srk.xisotool.util.Toasts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    result: TaskState.Finished?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Resultado") })
        },
    ) { innerPadding ->
        if (result == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nada para mostrar ainda.")
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onDone, shape = RoundedCornerShape(24.dp)) {
                    Text("Voltar ao início")
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            val (icon, blobColor, contentColor) = when {
                result.success -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                result.errorCode == ErrorCodes.CANCELLED -> Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                else -> Triple(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }

            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(MaterialShapes.Pill.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(72.dp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = when {
                    result.success && result.op == OpType.EXTRACT -> "ISO extraída!"
                    result.success -> "ISO criada!"
                    result.errorCode == ErrorCodes.CANCELLED -> "Operação cancelada"
                    else -> "Algo deu errado"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = Texts.messageFor(
                    errorCode = result.errorCode,
                    detail = result.message,
                    successText = when (result.op) {
                        OpType.EXTRACT -> "Os arquivos do jogo foram extraídos com a estrutura original, incluindo o default.xbe."
                        OpType.CREATE -> "A imagem XISO foi criada e pode ser usada no xemu ou gravada para o Xbox."
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            result.resultPath?.let { path ->
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Caminho",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (result.success && result.op == OpType.CREATE) {
                    Button(
                        onClick = {
                            val uri = XisoForegroundService.shareUri(context, path)
                            if (uri == null) {
                                Toasts.show(context, "Arquivo não encontrado")
                            } else {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(send, "Compartilhar ISO"))
                                }.onFailure {
                                    Toasts.show(context, "Nenhum app disponível para compartilhar")
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(imageVector = Icons.Rounded.Share, contentDescription = null)
                        Text("  Compartilhar ISO", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (result.success && result.op == OpType.EXTRACT) {
                    OutlinedButton(
                        onClick = {
                            val folder = Uri.parse("file://$path")
                            val open = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(folder, "resource/folder")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(open, "Abrir pasta"))
                            }.onFailure {
                                Toasts.show(context, "Não foi possível abrir a pasta")
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(imageVector = Icons.Rounded.FolderOpen, contentDescription = null)
                        Text("  Abrir pasta", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedButton(
                    onClick = {
                        Texts.copyToClipboard(context, path)
                        Toasts.show(context, "Caminho copiado")
                    },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null)
                    Text("  Copiar caminho", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onDone,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Concluir", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
