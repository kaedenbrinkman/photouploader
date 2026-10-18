package com.photopipeline.app.api

/** User is not signed in — use local estimate / show link-account UI instead of crashing. */
class NotSignedInException : Exception("not signed in")

/** HTTP or parse failure talking to the backend. */
class ApiRequestException(message: String, val httpCode: Int? = null) : Exception(message)
