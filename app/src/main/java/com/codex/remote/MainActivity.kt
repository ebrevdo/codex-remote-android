package com.codex.remote

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.remote.ui.CodexRemoteApp
import com.codex.remote.ui.theme.CodexRemoteTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Keep app and widget resources in English without changing the device locale.
        val configuration = Configuration().apply {
            setLocale(Locale.ENGLISH)
            setLayoutDirection(Locale.ENGLISH)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CodexRemoteTheme {
                val appViewModel: AppViewModel = viewModel()
                CodexRemoteApp(appViewModel)
            }
        }
    }
}
