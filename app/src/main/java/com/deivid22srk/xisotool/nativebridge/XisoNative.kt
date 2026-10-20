package com.deivid22srk.xisotool.nativebridge

/**
 * Ponte com o núcleo Rust (libxiso.so).
 *
 * O callback é chamado de threads nativas do Rust — as implementações devem
 * apenas publicar estado (StateFlow / NotificationManager), nunca tocar na UI.
 */
interface XisoTaskCallback {
    fun onProgress(processedBytes: Long, totalBytes: Long, currentFile: String?)

    /** [errorCode] 0 = sucesso. Os demais códigos estão em [com.deivid22srk.xisotool.data.ErrorCodes]. */
    fun onFinished(errorCode: Int, message: String?)
}

object XisoNative {
    init {
        System.loadLibrary("xiso")
    }

    /** Inicia a extração e retorna imediatamente o id da operação (negativo = falha ao iniciar). */
    external fun extractIso(isoPath: String, outDir: String, callback: XisoTaskCallback): Long

    /** Inicia a criação da ISO e retorna imediatamente o id da operação. */
    external fun createIso(srcDir: String, outIso: String, callback: XisoTaskCallback): Long

    /** Solicita o cancelamento cooperativo da operação. */
    external fun cancel(opId: Long)

    /** Verificação rápida de volume XDVDFS (setor 32, com offsets XGD1/2/3). */
    external fun checkXiso(isoPath: String): Boolean
}
