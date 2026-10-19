package com.photopipeline.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Friendly server processing pipeline labels (not raw DynamoDB enums). */
enum class ProcessingPipelineStep(val label: String) {
    StartingServer("Starting server"),
    SettingUp("Setting up"),
    Rendering("Rendering"),
    Publishing("Publishing to Google Photos"),
    Done("Done"),
}

fun resolveProcessingPipelineStep(
    state: String,
    phase: String?,
    finalizeInProgress: Boolean,
): ProcessingPipelineStep {
    val p = (phase ?: state).uppercase()
    val st = state.uppercase()
    return when {
        finalizeInProgress || st == "READY_TO_PROCESS" || p.contains("LAUNCH") -> ProcessingPipelineStep.StartingServer
        p.contains("BOOTSTRAP") || p.contains("SETUP") || p.contains("INSTALL") -> ProcessingPipelineStep.SettingUp
        p.contains("PUBLISH") -> ProcessingPipelineStep.Publishing
        st == "DONE" || st == "PARTIAL" || p.contains("DONE") -> ProcessingPipelineStep.Done
        p.contains("RENDER") || st == "PROCESSING" -> ProcessingPipelineStep.Rendering
        else -> ProcessingPipelineStep.StartingServer
    }
}

enum class FileProcessingBadge(val label: String) {
    Queued("Queued"),
    Rendering("Rendering"),
    Rendered("Rendered"),
    Published("Published"),
    Failed("Failed"),
}

fun fileProcessingBadge(serverState: String?): FileProcessingBadge {
    when (serverState?.uppercase()?.replace('-', '_')) {
        "PUBLISHED", "DONE" -> return FileProcessingBadge.Published
        "RENDERED" -> return FileProcessingBadge.Rendered
        "RENDERING", "IN_PROGRESS", "PROCESSING" -> return FileProcessingBadge.Rendering
        "FAILED", "ERROR" -> return FileProcessingBadge.Failed
        null, "", "QUEUED", "PENDING", "UPLOADED" -> return FileProcessingBadge.Queued
        else -> return FileProcessingBadge.Queued
    }
}

fun captureDateKey(epochMs: Long): String {
    if (epochMs <= 0L) return "unknown"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
}

fun captureDateHeading(epochMs: Long): String {
    if (epochMs <= 0L) return "Unknown date"
    return SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
}

fun computeFlowStep(shoot: com.photopipeline.app.data.ShootEntity?, files: List<com.photopipeline.app.data.FileEntity>): ShootFlowStep {
    if (shoot == null) {
        return if (files.isEmpty()) ShootFlowStep.PickFiles else ShootFlowStep.Upload
    }
    val terminal = setOf("DONE", "FAILED", "PARTIAL")
    return when {
        shoot.albumId != null || (shoot.state in terminal && shoot.state != "FAILED") -> ShootFlowStep.Results
        files.isNotEmpty() && files.all { it.uploadState == "UPLOADED" } -> ShootFlowStep.Processing
        shoot.state !in setOf("LOCAL", "IMPORTING") && shoot.backendState != null -> ShootFlowStep.Processing
        files.isEmpty() -> ShootFlowStep.PickFiles
        else -> ShootFlowStep.Upload
    }
}
