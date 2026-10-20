package com.deivid22srk.xisotool.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.deivid22srk.xisotool.data.OpType
import com.deivid22srk.xisotool.data.TaskState
import com.deivid22srk.xisotool.data.XisoTaskBus
import com.deivid22srk.xisotool.service.XisoForegroundService
import kotlinx.coroutines.flow.StateFlow

class XisoViewModel : ViewModel() {

    val taskState: StateFlow<TaskState> = XisoTaskBus.state
    val cancelRequested: StateFlow<Boolean> = XisoTaskBus.cancelRequested

    fun isBusy(): Boolean = XisoTaskBus.state.value is TaskState.Running

    fun startOperation(context: Context, op: OpType, src: String, dst: String) {
        if (isBusy()) return
        XisoTaskBus.resultPath = dst
        XisoForegroundService.start(context, op, src, dst)
    }

    fun requestCancel(context: Context) {
        XisoTaskBus.cancelRequested.value = true
        XisoForegroundService.requestCancel(context)
    }

    fun consumeResult() {
        XisoTaskBus.reset()
    }
}
