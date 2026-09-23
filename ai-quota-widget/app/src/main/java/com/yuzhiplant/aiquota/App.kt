package com.yuzhiplant.aiquota

import android.app.Application
import com.yuzhiplant.aiquota.work.RefreshScheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshScheduler.ensurePeriodic(this)
    }
}
