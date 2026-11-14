package com.pixiwall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pixiwall.ui.App
import com.pixiwall.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        Watchdog.acquire()
        Diag.i("MainActivity created")
        enableEdgeToEdge()
        val store = Store.get(this)
        setContent { AppTheme { App(store) } }
    }

    override fun onDestroy() {
        Diag.i("MainActivity destroyed")
        Watchdog.release()
        super.onDestroy()
    }
}
