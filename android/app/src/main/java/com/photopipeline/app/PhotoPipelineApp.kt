package com.photopipeline.app

import android.app.Application
import androidx.work.Configuration
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.AppDatabase
import com.photopipeline.app.data.Repository
import com.photopipeline.app.mtp.CanonMtpRepository
import com.photopipeline.app.mtp.UsbCameraManager
import com.photopipeline.app.pricing.LocalPricingEstimator
import com.photopipeline.app.upload.UploadPreferences

class PhotoPipelineApp : Application(), Configuration.Provider {
    lateinit var repository: Repository
        private set
    lateinit var uploadPreferences: UploadPreferences
        private set
    lateinit var usbCameraManager: UsbCameraManager
        private set

    override fun onCreate() {
        super.onCreate()
        PipelineLog.d("app", "PhotoPipelineApp.onCreate")
        usbCameraManager = UsbCameraManager(applicationContext)
        usbCameraManager.start()
        val pricingJson = assets.open("pricing.json").bufferedReader().use { it.readText() }
        val localPricing = LocalPricingEstimator(pricingJson)
        uploadPreferences = UploadPreferences(this)
        val db = AppDatabase.build(this)
        repository = Repository(
            db,
            applicationContext,
            CanonMtpRepository(usbCameraManager),
            usbCameraManager,
            localPricing,
            uploadPreferences,
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
