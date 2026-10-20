package com.deivid22srk.xisotool.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.text.format.Formatter
import java.util.Locale

object Paths {

    fun storageRoot(): String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    fun downloads(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun documents(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
}

object Formats {

    fun size(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)

    fun speed(context: Context, bytesPerSecond: Float): String {
        if (bytesPerSecond <= 0f) return "—"
        return "${Formatter.formatShortFileSize(context, bytesPerSecond.toLong())}/s"
    }

    fun eta(context: Context, remainingBytes: Long, bytesPerSecond: Float): String {
        if (bytesPerSecond <= 0f || remainingBytes <= 0) return "—"
        val seconds = (remainingBytes / bytesPerSecond).toLong()
        val time = when {
            seconds >= 3600 -> String.format(Locale.getDefault(), "%dh%02dm", seconds / 3600, (seconds % 3600) / 60)
            seconds >= 60 -> String.format(Locale.getDefault(), "%dm%02ds", seconds / 60, seconds % 60)
            else -> String.format(Locale.getDefault(), "%ds", seconds)
        }
        return "$time restantes"
    }
}

object Texts {

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "jogo" }

    fun messageFor(errorCode: Int, detail: String?, successText: String): String = when (errorCode) {
        com.deivid22srk.xisotool.data.ErrorCodes.OK -> successText
        com.deivid22srk.xisotool.data.ErrorCodes.INVALID_ISO ->
            "O arquivo não é uma imagem XISO válida do Xbox clássico (ou usa um formato não suportado)."
        com.deivid22srk.xisotool.data.ErrorCodes.NO_DEFAULT_XBE ->
            "A pasta do jogo precisa conter o arquivo default.xbe na raiz."
        com.deivid22srk.xisotool.data.ErrorCodes.CANCELLED ->
            "A operação foi cancelada."
        com.deivid22srk.xisotool.data.ErrorCodes.NO_SPACE ->
            "Espaço insuficiente no armazenamento para concluir a operação."
        com.deivid22srk.xisotool.data.ErrorCodes.SOURCE_MISSING ->
            "Arquivo ou pasta de origem não encontrado."
        else -> detail?.let { "Erro durante a operação: $it" } ?: "Erro inesperado durante a operação."
    }

    fun copyToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("XISO Tool", text))
    }
}
