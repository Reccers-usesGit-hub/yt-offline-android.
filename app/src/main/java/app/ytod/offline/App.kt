package app.ytod.offline

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.ytod.offline.data.Store
import app.ytod.offline.dl.NpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        NewPipe.init(NpDownloader.instance, Localization("en", "US"), ContentCountry("US"))
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: App
            private set
    }
}
