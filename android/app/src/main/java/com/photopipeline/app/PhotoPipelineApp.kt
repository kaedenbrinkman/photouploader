package com.photopipeline.app

import android.app.Application
import androidx.work.Configuration
import com.photopipeline.app.data.AppDatabase
import com.photopipeline.app.data.Repository
import com.photopipeline.app.mtp.CanonMtpRepository
import com.photopipeline.app.mtp.UsbCameraManager

class PhotoPipelineApp : Application(), Configuration.Provider {
    lateinit var repository: Repository
        private set
    lateinit var usbCameraManager: UsbCameraManager
        private set

    override fun onCreate() {
        super.onCreate()
        usbCameraManager = UsbCameraManager(applicationContext)
        usbCameraManager.start()
        val db = AppDatabase.build(this)
        repository = Repository(
            db,
            applicationContext,
            CanonMtpRepository(usbCameraManager),
            usbCameraManager,
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
