package io.kognis.tactical

import android.app.Application
import androidx.work.Configuration

class KognisLiteApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
    }

    // WorkManager needs manual init for multi-process configurations.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
