package com.sixthsense.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.sixthsense.prototype.ui.SixthSenseApp
import com.sixthsense.prototype.viewmodel.SixthSenseViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SixthSenseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SixthSenseApp(viewModel) }
    }

    override fun onStop() {
        viewModel.onHostStopped()
        super.onStop()
    }
}
