package com.deivid22srk.xisotool.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Códigos de erro espelham os do núcleo Rust (rust/src/error.rs). */
object ErrorCodes {
    const val OK = 0
    const val INVALID_ISO = 1
    const val NO_DEFAULT_XBE = 2
    const val IO = 3
    const val CANCELLED = 4
    const val NO_SPACE = 5
    const val SOURCE_MISSING = 6
    const val UNKNOWN = 7
}

enum class OpType {
    EXTRACT,
    CREATE,
}

data class TaskProgress(
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String? = null,
)

sealed interface TaskState {
    data object Idle : TaskState

    data class Running(
        val op: OpType,
        val progress: TaskProgress = TaskProgress(),
    ) : TaskState

    data class Finished(
        val op: OpType,
        val success: Boolean,
        val errorCode: Int,
        val message: String?,
        val resultPath: String?,
    ) : TaskState
}

/**
 * Barramento de estado compartilhado entre o serviço em primeiro plano e a UI.
 * Sobrevive a recriação da Activity e mantém a tela de progresso sincronizada
 * mesmo que o app seja reaberto no meio de uma operação.
 */
object XisoTaskBus {
    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private val _cancelRequested = MutableStateFlow(false)
    val cancelRequested: StateFlow<Boolean> = _cancelRequested.asStateFlow()

    val currentOpId = AtomicLong(0)

    /** Caminho produzido pela operação (pasta extraída ou ISO criada). */
    @Volatile
    var resultPath: String? = null

    fun begin(op: OpType) {
        _cancelRequested.value = false
        currentOpId.set(0)
        resultPath = null
        _state.value = TaskState.Running(op)
    }

    fun updateProgress(processed: Long, total: Long, currentFile: String?) {
        val current = _state.value
        if (current is TaskState.Running) {
            _state.value = current.copy(
                progress = TaskProgress(
                    processedBytes = processed,
                    totalBytes = total,
                    currentFile = currentFile ?: current.progress.currentFile,
                ),
            )
        }
    }

    fun finish(success: Boolean, errorCode: Int, message: String?) {
        val op = (_state.value as? TaskState.Running)?.op ?: OpType.EXTRACT
        _state.value = TaskState.Finished(
            op = op,
            success = success,
            errorCode = errorCode,
            message = message,
            resultPath = resultPath,
        )
    }

    fun reset() {
        _state.value = TaskState.Idle
        resultPath = null
        currentOpId.set(0)
    }
}
