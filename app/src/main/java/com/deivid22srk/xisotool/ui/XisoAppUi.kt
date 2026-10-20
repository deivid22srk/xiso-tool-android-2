package com.deivid22srk.xisotool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.deivid22srk.xisotool.data.TaskState
import com.deivid22srk.xisotool.ui.screens.CreateScreen
import com.deivid22srk.xisotool.ui.screens.ExtractScreen
import com.deivid22srk.xisotool.ui.screens.HomeScreen
import com.deivid22srk.xisotool.ui.screens.PermissionScreen
import com.deivid22srk.xisotool.ui.screens.ProgressScreen
import com.deivid22srk.xisotool.ui.screens.ResultScreen
import com.deivid22srk.xisotool.util.StorageAccess

enum class Screen {
    Home,
    Extract,
    Create,
    Progress,
    Result,
    Permission,
}

@Composable
fun XisoAppRoot(viewModel: XisoViewModel) {
    val context = LocalContext.current
    val taskState by viewModel.taskState.collectAsState()
    var hasStorage by rememberStorageAccessState()
    var screenName by rememberSaveable {
        mutableStateOf(if (hasStorage) Screen.Home.name else Screen.Permission.name)
    }

    // Reabrir o app no meio de uma operação leva direto para o progresso/resultado.
    LaunchedEffect(taskState) {
        val current = Screen.valueOf(screenName)
        when (val state = taskState) {
            is TaskState.Running -> if (current != Screen.Progress) screenName = Screen.Progress.name
            is TaskState.Finished -> if (current == Screen.Progress) screenName = Screen.Result.name
            TaskState.Idle -> {
                if (current == Screen.Progress || current == Screen.Result) {
                    screenName = Screen.Home.name
                }
            }
        }
    }

    BackHandler(
        enabled = screenName != Screen.Home.name && screenName != Screen.Permission.name,
    ) {
        screenName = Screen.Home.name
    }

    AnimatedContent(
        targetState = Screen.valueOf(screenName),
        transitionSpec = {
            val forward = targetState.ordinal >= initialState.ordinal
            val intSpec = spring<Int>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            val floatSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            (
                slideInHorizontally(intSpec) { full -> if (forward) full / 4 else -full / 4 } +
                    fadeIn(floatSpec)
                ) togetherWith (
                slideOutHorizontally(intSpec) { full -> if (forward) -full / 4 else full / 4 } +
                    fadeOut(floatSpec)
                )
        },
        label = "xiso-screens",
    ) { current ->
        when (current) {
            Screen.Permission -> PermissionScreen()

            Screen.Home -> HomeScreen(
                taskState = taskState,
                onExtract = { screenName = Screen.Extract.name },
                onCreate = { screenName = Screen.Create.name },
                onOpenProgress = { screenName = Screen.Progress.name },
            )

            Screen.Extract -> ExtractScreen(
                isBusy = viewModel.isBusy(),
                onStart = { iso, outDir -> viewModel.startOperation(context, com.deivid22srk.xisotool.data.OpType.EXTRACT, iso, outDir) },
                onBack = { screenName = Screen.Home.name },
            )

            Screen.Create -> CreateScreen(
                isBusy = viewModel.isBusy(),
                onStart = { src, outIso -> viewModel.startOperation(context, com.deivid22srk.xisotool.data.OpType.CREATE, src, outIso) },
                onBack = { screenName = Screen.Home.name },
            )

            Screen.Progress -> ProgressScreen(
                running = taskState as? TaskState.Running,
                cancelRequested = viewModel.cancelRequested.collectAsState().value,
                onCancel = { viewModel.requestCancel(context) },
                onBack = { screenName = Screen.Home.name },
            )

            Screen.Result -> ResultScreen(
                result = taskState as? TaskState.Finished,
                onDone = {
                    viewModel.consumeResult()
                    screenName = Screen.Home.name
                },
            )
        }
    }
}

@Composable
private fun rememberStorageAccessState(): androidx.compose.runtime.State<Boolean> {
    val context = LocalContext.current
    val state = androidx.compose.runtime.remember {
        mutableStateOf(StorageAccess.hasFullAccess(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = StorageAccess.hasFullAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
