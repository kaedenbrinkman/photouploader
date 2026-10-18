package com.photopipeline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.photopipeline.app.ui.PhotoPipelineNavHost
import com.photopipeline.app.ui.theme.PhotoPipelineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PhotoPipelineApp
        setContent {
            PhotoPipelineTheme {
                PhotoPipelineNavHost(repository = app.repository)
            }
        }
    }
}
