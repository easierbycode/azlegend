package com.azlegend.wear

import android.app.Application
import android.content.Context

class AzLegendApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as AzLegendApp).container
    }
}
