package com.photopipeline.app.mtp

import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo as AndroidMtpObjectInfo
import com.photopipeline.app.core.PipelineLog

/**
 * Canon cameras often store CR3 under DCIM/100CANON with object format 0x3000 (undefined),
 * so we list with format=0 and walk association/undefined folder handles — not root-only
 * [MtpConstants.FORMAT_UNDEFINED] queries.
 */
object MtpMediaEnumerator {
    private const val PARENT_ROOT_INT_MIN = 0
    private const val PARENT_ROOT_FFFF = 0xFFFFFFFF.toInt()
    private const val SAMPLE_LOG_LIMIT = 20

    fun enumerateMedia(device: MtpDevice): List<MtpObjectInfo> {
        val storageIds = device.storageIds ?: intArrayOf()
        if (storageIds.isEmpty()) {
            PipelineLog.w("mtp-enum", "no storageIds")
            return emptyList()
        }
        val out = mutableListOf<MtpObjectInfo>()
        var sampleLogged = 0
        for (storageId in storageIds) {
            PipelineLog.d("mtp-enum", "=== storageId=$storageId ===")
            val handles = collectAllHandles(device, storageId)
            PipelineLog.d("mtp-enum", "storage $storageId total handles after walk: ${handles.size}")
            var mediaCount = 0
            for (handle in handles) {
                val info = getObjectInfoWithRetry(device, handle) ?: continue
                val name = info.name ?: continue
                if (sampleLogged < SAMPLE_LOG_LIMIT) {
                    logObjectSample(handle, info)
                    sampleLogged++
                }
                if (MtpObjectFormat.isFolder(info.format, name)) continue
                if (!MtpObjectFormat.isMediaFileName(name)) continue
                mediaCount++
                val captureMs = info.dateCreated.toLong() * 1000L
                out += MtpObjectInfo(
                    storageId = storageId.toLong(),
                    handle = handle.toLong(),
                    filename = name,
                    sizeBytes = info.compressedSize.toLong(),
                    captureTimeEpochMs = captureMs,
                )
            }
            PipelineLog.d("mtp-enum", "storage $storageId media files by extension: $mediaCount")
        }
        PipelineLog.d("mtp-enum", "enumerateMedia total media: ${out.size}")
        return out
    }

    private fun collectAllHandles(device: MtpDevice, storageId: Int): Set<Int> {
        val seen = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()

        for ((parent, label) in listOf(PARENT_ROOT_FFFF to "0xFFFFFFFF", PARENT_ROOT_INT_MIN to "0")) {
            val roots = device.getObjectHandles(
                storageId,
                MtpObjectFormat.FORMAT_ALL_OBJECTS,
                parent,
            )
            val count = roots?.size ?: 0
            PipelineLog.d(
                "mtp-enum",
                "getObjectHandles(storage=$storageId format=0 parent=$label) -> $count",
            )
            roots?.forEach { h ->
                if (h !in seen) queue.add(h)
            }
        }

        while (queue.isNotEmpty()) {
            val handle = queue.removeFirst()
            if (!seen.add(handle)) continue
            val info = device.getObjectInfo(handle) ?: continue
            if (!MtpObjectFormat.isFolder(info.format, info.name ?: "")) continue
            val children = device.getObjectHandles(
                storageId,
                MtpObjectFormat.FORMAT_ALL_OBJECTS,
                handle,
            )
            val childCount = children?.size ?: 0
            PipelineLog.d(
                "mtp-enum",
                "folder \"${info.name}\" handle=$handle fmt=${MtpObjectFormat.formatHex(info.format)} children=$childCount",
            )
            children?.forEach { child ->
                if (child !in seen) queue.add(child)
            }
        }
        return seen
    }

    private fun logObjectSample(handle: Int, info: AndroidMtpObjectInfo) {
        PipelineLog.d(
            "mtp-enum",
            "sample h=$handle name=\"${info.name}\" fmt=${MtpObjectFormat.formatHex(info.format)} " +
                "parent=${info.parent} size=${info.compressedSize}",
        )
    }

    private fun getObjectInfoWithRetry(device: MtpDevice, handle: Int, attempts: Int = 3): AndroidMtpObjectInfo? {
        repeat(attempts) { attempt ->
            val info = device.getObjectInfo(handle)
            if (info != null) return info
            if (attempt < attempts - 1) Thread.sleep(50L * (attempt + 1))
        }
        return null
    }
}
