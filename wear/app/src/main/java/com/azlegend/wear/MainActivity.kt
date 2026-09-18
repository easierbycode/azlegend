package com.azlegend.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.azlegend.wear.ui.WearApp

class MainActivity : ComponentActivity() {

    /** Bumped whenever the media notification asks us to show the player. */
    private var openPlayerRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        if (savedInstanceState == null && intent?.action == ACTION_OPEN_PLAYER) openPlayerRequests++
        setContent {
            WearApp(container = AzLegendApp.container(this), openPlayerRequests = openPlayerRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_PLAYER) openPlayerRequests++
    }

    override fun onStart() {
        super.onStart()
        AzLegendApp.container(this).player.onStart()
    }

    override fun onStop() {
        AzLegendApp.container(this).player.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        AzLegendApp.container(this).player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.azlegend.wear.action.OPEN_PLAYER"
    }
}
