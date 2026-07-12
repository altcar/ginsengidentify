package com.example.ginsengidentifier

import android.app.Application
import android.util.Log

class GinsengApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "App started")
    }

    companion object {
        private const val TAG = "GinsengApp"
    }
}
