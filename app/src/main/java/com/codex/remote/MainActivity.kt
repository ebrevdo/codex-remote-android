package com.codex.remote

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.codex.remote.ui.CodexRemoteApp
import com.codex.remote.ui.theme.CodexRemoteTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var listeningForNetwork = false
    private var defaultNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!listeningForNetwork) return
            defaultNetwork = network
            appViewModel.onNetworkChanged(true)
        }

        override fun onLost(network: Network) {
            if (!listeningForNetwork || defaultNetwork != network) return
            defaultNetwork = null
            appViewModel.onNetworkChanged(false)
        }
    }

    override fun onStart() {
        super.onStart()
        defaultNetwork = connectivity.activeNetwork
        appViewModel.onNetworkChanged(defaultNetwork != null)
        appViewModel.setAppForeground(true)
        listeningForNetwork = true
        connectivity.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper()))
    }

    override fun onStop() {
        listeningForNetwork = false
        connectivity.unregisterNetworkCallback(networkCallback)
        appViewModel.setAppForeground(false)
        super.onStop()
    }

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
        appViewModel = ViewModelProvider(this)[AppViewModel::class.java]
        setContent {
            CodexRemoteTheme {
                CodexRemoteApp(appViewModel)
            }
        }
    }
}
