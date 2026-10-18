package com.photopipeline.app.data

import com.photopipeline.app.api.EstimateResponse

enum class EstimateSource {
    BACKEND,
    LOCAL,
}

data class StoredEstimate(val response: EstimateResponse, val source: EstimateSource)
