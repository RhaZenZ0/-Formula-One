package com.rhazenz0.f1info

import android.app.Application

class F1InfoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        com.rhazenz0.f1info.data.update.UpdateManager.init(this)
    }
}
