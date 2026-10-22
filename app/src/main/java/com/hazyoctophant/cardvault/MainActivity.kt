package com.hazyoctophant.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hazyoctophant.cardvault.ui.CardVaultNavHost
import com.hazyoctophant.cardvault.ui.components.AppBackground
import com.hazyoctophant.cardvault.ui.theme.CardVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = (application as CardVaultApp).container.settings.settings
        setContent {
            val current by settings.collectAsStateWithLifecycle()
            CardVaultTheme(current) {
                AppBackground {
                    CardVaultNavHost()
                }
            }
        }
    }
}
