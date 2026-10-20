package com.deivid22srk.xisotool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.deivid22srk.xisotool.ui.XisoAppRoot
import com.deivid22srk.xisotool.ui.XisoViewModel
import com.deivid22srk.xisotool.ui.theme.XisoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: XisoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XisoTheme {
                XisoAppRoot(viewModel = viewModel)
            }
        }
    }
}
