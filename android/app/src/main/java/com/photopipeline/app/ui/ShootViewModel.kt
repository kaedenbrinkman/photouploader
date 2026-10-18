package com.photopipeline.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photopipeline.app.api.EstimateResponse
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.Repository
import com.photopipeline.app.data.ShootEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ShootViewModel(private val repo: Repository, private val shootId: String) : ViewModel() {
    init {
        viewModelScope.launch {
            if (repo.getShoot(shootId) == null) {
                repo.db.shootDao().upsert(
                    com.photopipeline.app.data.ShootEntity(
                        shootId = shootId,
                        albumTitle = "Shoot ${shootId.take(8)}",
                        state = "IMPORTING",
                        phase = "IMPORT",
                        createdAt = System.currentTimeMillis(),
                        imageCount = 0,
                        totalBytes = 0,
                        estimateJson = null,
                        backendState = null,
                        albumId = null,
                        actualComputeJson = null,
                    ),
                )
            }
        }
    }
    val shoot: StateFlow<ShootEntity?> =
        repo.observeShoot(shootId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val files: StateFlow<List<FileEntity>> =
        repo.observeFiles(shootId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var estimate: EstimateResponse? = null
        private set

    private var pollJob: Job? = null

    fun connectCamera() {
        viewModelScope.launch {
            repo.importFromCamera(shootId, File("/data/data/com.photopipeline.app/files/imports/$shootId"))
        }
    }

    fun loadEstimate() {
        viewModelScope.launch { estimate = repo.fetchAndStoreEstimate(shootId) }
    }

    fun startUpload() {
        repo.enqueueUploads(shootId)
        startPolling()
    }

    fun submitShoot() {
        viewModelScope.launch {
            repo.submitShoot(shootId)
            startPolling()
        }
    }

    fun retryFailedUploads() {
        viewModelScope.launch {
            files.value.filter { it.uploadState == "FAILED" }.forEach { repo.retryFile(it.identityKey) }
            repo.enqueueUploads(shootId)
        }
    }

    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                runCatching { repo.refreshBackendStatus(shootId) }
                val state = shoot.value?.state
                if (state in TERMINAL) break
                delay(10_000)
            }
        }
    }

    companion object {
        private val TERMINAL = setOf("DONE", "FAILED", "PARTIAL")
        fun factory(repo: Repository, shootId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShootViewModel(repo, shootId) as T
        }
    }
}

fun newShootId(): String = UUID.randomUUID().toString()
