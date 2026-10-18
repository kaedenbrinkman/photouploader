package com.photopipeline.app.upload

data class UploadPart(val partNumber: Int, val eTag: String)

object UploadParts {
    fun parse(json: String?): List<UploadPart> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        val trimmed = json.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split("},").map { chunk ->
            val c = chunk.trim().trimEnd('}').trimStart('{')
            var part = 0
            var tag = ""
            c.split(',').forEach { kv ->
                val p = kv.split(':').map { it.trim().trim('"') }
                if (p.size >= 2) {
                    when (p[0]) {
                        "partNumber" -> part = p[1].toInt()
                        "eTag" -> tag = p[1]
                    }
                }
            }
            UploadPart(part, tag)
        }
    }

    fun toJson(parts: List<UploadPart>): String {
        if (parts.isEmpty()) return "[]"
        return parts.sortedBy { it.partNumber }.joinToString(prefix = "[", postfix = "]") { p ->
            """{"partNumber":${p.partNumber},"eTag":"${p.eTag.replace("\"", "\\\"")}"}"""
        }
    }

    fun merge(existing: List<UploadPart>, newPart: UploadPart): List<UploadPart> {
        val map = existing.associateBy { it.partNumber }.toMutableMap()
        map[newPart.partNumber] = newPart
        return map.values.sortedBy { it.partNumber }
    }
}
