package com.alexskoropletov.magic

import android.app.Application

class MagicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.setProperty("user.home", filesDir.absolutePath)
    }
}
