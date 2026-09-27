package com.dsh.phoneact

import android.app.Application

class PhoneActApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.dsh.phoneact.core.Lg.init(this)
        com.dsh.phoneact.core.Prefs.init(this)
        com.dsh.phoneact.core.ActCore.init(this)
    }

    companion object {
        lateinit var instance: PhoneActApp
            private set
    }
}
