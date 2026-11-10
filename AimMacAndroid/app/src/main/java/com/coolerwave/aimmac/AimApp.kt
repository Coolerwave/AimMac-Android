package com.coolerwave.aimmac

import android.app.Application

class AimApp : Application() {
    lateinit var session: PointerSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = PointerSession(this)
    }
}
