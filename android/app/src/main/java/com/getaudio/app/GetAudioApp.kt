package com.getaudio.app

import android.app.Application
import com.getaudio.app.sync.SyncWorker

class GetAudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 周期性同步：每 6 小时，仅在 WiFi 下
        SyncWorker.schedule(this)
    }
}
