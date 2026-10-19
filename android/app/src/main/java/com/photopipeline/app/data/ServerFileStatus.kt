package com.photopipeline.app.data

data class ServerFileStatus(
    val sha256: String,
    val state: String,
    val originalName: String,
    val error: String?,
)
