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

data class ProcessingPipelineUi(
    val activeStep: ProcessingPipelineStep,
    val failedStep: ProcessingPipelineStep? = null,
)

private val TERMINAL_FAIL_PHASES = setOf(
    "RUNTIME_TIMEOUT",
    "STALLED",
    "GP_FAILED",
    "RENDER_FAILED",
    "BOOTSTRAP_TIMEOUT",
    "FAILED",
)

fun isTerminalProcessingState(state: String): Boolean {
    val st = state.uppercase()
    return st in setOf("FAILED", "DONE", "PARTIAL")
}

fun resolveProcessingPipelineUi(
    state: String,
    phase: String?,
    finalizeInProgress: Boolean,
): ProcessingPipelineUi {
    val st = state.uppercase()
    val ph = (phase ?: state).uppercase()
    val failedStep = when {
        st == "FAILED" || ph in TERMINAL_FAIL_PHASES -> failedPipelineStepForPhase(ph, st)
        else -> null
    }
    if (failedStep != null) {
        return ProcessingPipelineUi(activeStep = failedStep, failedStep = failedStep)
    }
    return ProcessingPipelineUi(
        activeStep = resolveProcessingPipelineStep(state, phase, finalizeInProgress),
    )
}

fun failedPipelineStepForPhase(phase: String, state: String): ProcessingPipelineStep {
    val p = phase.uppercase()
    return when {
        p.contains("BOOTSTRAP") || p == "BOOTSTRAP_TIMEOUT" -> ProcessingPipelineStep.SettingUp
        p.startsWith("GP_") || p == "GP_FAILED" || p.contains("PUBLISH") -> ProcessingPipelineStep.Publishing
        p.contains("RENDER") || p == "RENDER_FAILED" || p == "STALLED" -> ProcessingPipelineStep.Rendering
        p.contains("LAUNCH") -> ProcessingPipelineStep.StartingServer
        p == "RUNTIME_TIMEOUT" && state == "PARTIAL" -> ProcessingPipelineStep.Publishing
        p == "RUNTIME_TIMEOUT" -> ProcessingPipelineStep.Rendering
        else -> ProcessingPipelineStep.Rendering
    }
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
        p == "GP_LOADING_TOKEN" || p == "GP_REFRESH_TOKEN" || p == "GP_SAVING_TOKEN" || p == "GP_TOKEN_READY" ->
            ProcessingPipelineStep.SettingUp
        p.startsWith("GP_") || p.contains("PUBLISH") -> ProcessingPipelineStep.Publishing
        p.contains("BOOTSTRAP") || p.contains("SETUP") || p.contains("INSTALL") -> ProcessingPipelineStep.SettingUp
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
        "GP_CREATED", "PUBLISHED", "DONE" -> return FileProcessingBadge.Published
        "RENDERED" -> return FileProcessingBadge.Rendered
        "RENDERING", "IN_PROGRESS", "PROCESSING" -> return FileProcessingBadge.Rendering
        "FAILED", "FAILED_RENDER", "PREVIEW_ONLY", "ERROR" -> return FileProcessingBadge.Failed
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
