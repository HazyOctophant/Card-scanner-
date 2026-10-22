package com.hazyoctophant.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hazyoctophant.cardvault.ui.CardVaultNavHost
import com.hazyoctophant.cardvault.ui.theme.CardVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CardVaultTheme {
                CardVaultNavHost()
            }
        }
    }
}
