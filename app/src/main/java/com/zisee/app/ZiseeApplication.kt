package com.zisee.app

import android.app.Application
import com.zisee.app.core.AppContainer

class ZiseeApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
