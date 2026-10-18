package com.photopipeline.app

import android.app.Application
import androidx.work.Configuration
import com.photopipeline.app.data.AppDatabase
import com.photopipeline.app.data.Repository

class PhotoPipelineApp : Application(), Configuration.Provider {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.build(this)
        repository = Repository(db, applicationContext)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
