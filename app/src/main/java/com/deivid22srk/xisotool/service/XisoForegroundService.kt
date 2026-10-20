package com.deivid22srk.xisotool.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.deivid22srk.xisotool.MainActivity
import com.deivid22srk.xisotool.R
import com.deivid22srk.xisotool.XisoApp
import com.deivid22srk.xisotool.data.ErrorCodes
import com.deivid22srk.xisotool.data.OpType
import com.deivid22srk.xisotool.data.XisoTaskBus
import com.deivid22srk.xisotool.nativebridge.XisoNative
import com.deivid22srk.xisotool.nativebridge.XisoTaskCallback
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Serviço em primeiro plano (dataSync) que executa a operação nativa:
 * sobrevive a tela desligada/app em segundo plano, mostra progresso real na
 * notificação (determinado, com botão Cancelar) e mantém um PARTIAL_WAKE_LOCK.
 */
class XisoForegroundService : Service() {

    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastNotificationUpdate = 0L

    @Volatile
    private var currentOp: OpType = OpType.EXTRACT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                requestCancelNative()
                return START_NOT_STICKY
            }

            ACTION_RUN -> Unit

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val op = intent?.getStringExtra(EXTRA_OP)?.let { runCatching { OpType.valueOf(it) }.getOrNull() }
        val src = intent?.getStringExtra(EXTRA_SRC)
        val dst = intent?.getStringExtra(EXTRA_DST)

        if (op == null || src.isNullOrBlank() || dst.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running.compareAndSet(false, true)) {
            // Já existe uma operação em andamento: publica algo para satisfazer o
            // contrato do startForegroundService e encerra.
            startForegroundCompat(neutralNotification())
            stopTask()
            return START_NOT_STICKY
        }

        currentOp = op
        XisoTaskBus.begin(op)
        acquireWakeLock()
        startForegroundCompat(buildProgressNotification(0, 0, null))

        val taskCallback = object : XisoTaskCallback {
            override fun onProgress(processedBytes: Long, totalBytes: Long, currentFile: String?) {
                XisoTaskBus.updateProgress(processedBytes, totalBytes, currentFile)
                maybeUpdateNotification(processedBytes, totalBytes, currentFile)
            }

            override fun onFinished(errorCode: Int, message: String?) {
                val success = errorCode == ErrorCodes.OK
                XisoTaskBus.finish(success, errorCode, message)
                showFinalNotification(success, message)
                stopTask()
            }
        }

        thread(name = "xiso-android-task") {
            val opId = try {
                when (op) {
                    OpType.EXTRACT -> XisoNative.extractIso(src, dst, taskCallback)
                    OpType.CREATE -> XisoNative.createIso(src, dst, taskCallback)
                }
            } catch (t: Throwable) {
                -1L
            }

            if (opId >= 0) {
                XisoTaskBus.currentOpId.set(opId)
            } else {
                taskCallback.onFinished(
                    ErrorCodes.UNKNOWN,
                    "Não foi possível iniciar a operação nativa (biblioteca Rust indisponível).",
                )
            }
        }

        return START_NOT_STICKY
    }

    /** Android 15+: limite de ~6h para FGS dataSync. Encerra com mensagem clara. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        requestCancelNative()
        XisoTaskBus.finish(
            success = false,
            errorCode = ErrorCodes.CANCELLED,
            message = "A operação atingiu o limite de tempo do sistema para serviços em segundo plano.",
        )
        showFinalNotification(false, "Limite de tempo do sistema atingido")
        stopTask()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun requestCancelNative() {
        XisoTaskBus.cancelRequested.value = true
        val opId = XisoTaskBus.currentOpId.get()
        if (opId != 0L) {
            runCatching { XisoNative.cancel(opId) }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XisoTool::operation").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                XisoApp.NOTIFICATION_PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(XisoApp.NOTIFICATION_PROGRESS_ID, notification)
        }
    }

    private fun maybeUpdateNotification(processed: Long, total: Long, currentFile: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdate < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationUpdate = now
        val notification = buildProgressNotification(processed, total, currentFile)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(XisoApp.NOTIFICATION_PROGRESS_ID, notification)
    }

    private fun operationTitle(): String = when (currentOp) {
        OpType.EXTRACT -> "Extraindo ISO..."
        OpType.CREATE -> "Criando ISO..."
    }

    private fun percentOf(processed: Long, total: Long): Int =
        if (total > 0) ((processed * 100) / total).toInt().coerceIn(0, 100) else 0

    private fun buildProgressNotification(processed: Long, total: Long, currentFile: String?): Notification {
        val percent = percentOf(processed, total)
        val fileLabel = currentFile?.substringAfterLast('/') ?: "Preparando..."
        val text = if (total > 0) "$percent% • $fileLabel" else fileLabel

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, XisoForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, XisoApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(operationTitle())
            .setContentText(text)
            .setProgress(100, percent, total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Cancelar", cancelIntent)
            .build()
    }

    private fun neutralNotification(): Notification =
        NotificationCompat.Builder(this, XisoApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("XISO Tool")
            .setContentText("Operação em andamento")
            .setOngoing(true)
            .build()

    private fun showFinalNotification(success: Boolean, message: String?) {
        val path = XisoTaskBus.resultPath
        val title = when {
            success && currentOp == OpType.EXTRACT -> "ISO extraída"
            success -> "ISO criada"
            else -> "A operação falhou"
        }
        val text = when {
            success && currentOp == OpType.EXTRACT -> path ?: "Arquivos extraídos"
            success -> path ?: "Imagem criada"
            else -> message ?: "Toque para ver os detalhes"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, XisoApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { "Concluído" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifEmpty { "Concluído" }))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(XisoApp.NOTIFICATION_RESULT_ID, notification)
    }

    private fun stopTask() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        running.set(false)
        stopSelf()
    }

    companion object {
        private const val ACTION_RUN = "com.deivid22srk.xisotool.action.RUN"
        private const val ACTION_CANCEL = "com.deivid22srk.xisotool.action.CANCEL"
        private const val EXTRA_OP = "extra_op"
        private const val EXTRA_SRC = "extra_src"
        private const val EXTRA_DST = "extra_dst"
        private const val NOTIFICATION_MIN_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context, op: OpType, src: String, dst: String) {
            val intent = Intent(context, XisoForegroundService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_OP, op.name)
                .putExtra(EXTRA_SRC, src)
                .putExtra(EXTRA_DST, dst)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestCancel(context: Context) {
            val intent = Intent(context, XisoForegroundService::class.java).setAction(ACTION_CANCEL)
            // startService (e não startForegroundService): apenas entrega a ação de
            // cancelamento e não pode violar o contrato de 5s do startForeground.
            context.startService(intent)
        }

        fun shareUri(context: Context, path: String): Uri? {
            val file = File(path)
            if (!file.exists()) return null
            return runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        }
    }
}
