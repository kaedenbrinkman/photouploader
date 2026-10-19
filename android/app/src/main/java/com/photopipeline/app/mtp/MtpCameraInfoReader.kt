package com.photopipeline.app.mtp

import android.mtp.MtpDevice
import com.photopipeline.app.core.PipelineLog

internal object MtpCameraInfoReader {
    private const val MTP_DEVICE_PROPERTY_BATTERY_LEVEL = 0x5001

    fun readSnapshot(mtp: MtpDevice): CameraMtpSnapshot? {
        val info = mtp.deviceInfo ?: return null
        val manufacturer = info.manufacturer?.trim().orEmpty()
        val model = info.model?.trim().orEmpty()
        val version = info.version?.trim().orEmpty()
        val maskedSerial = CameraMtpFormat.maskSerialNumber(info.serialNumber)
        PipelineLog.d(
            "mtp",
            "deviceInfo manufacturer=$manufacturer model=$model fw=$version storageCount=${mtp.storageIds?.size ?: 0}",
        )
        val storages = readStorageVolumes(mtp)
        val battery = readBatteryPercent(mtp)
        return CameraMtpSnapshot(
            title = CameraMtpFormat.cameraDisplayTitle(manufacturer, model),
            firmwareVersion = version,
            maskedSerial = maskedSerial,
            storageVolumes = storages,
            batteryPercent = battery,
        )
    }

    private fun readStorageVolumes(mtp: MtpDevice): List<CameraStorageSnapshot> {
        val ids = mtp.storageIds ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<CameraStorageSnapshot>(ids.size)
        for (id in ids) {
            val storage = mtp.getStorageInfo(id) ?: continue
            out.add(
                CameraStorageSnapshot(
                    description = storage.description?.trim().orEmpty(),
                    maxCapacityBytes = storage.maxCapacity.coerceAtLeast(0L),
                    freeSpaceBytes = storage.freeSpace.coerceAtLeast(0L),
                ),
            )
        }
        return out
    }

    fun readBatteryPercent(mtp: MtpDevice): Int? {
        return try {
            val method = MtpDevice::class.java.getMethod(
                "getDevicePropValue",
                Int::class.javaPrimitiveType,
            )
            val raw = method.invoke(mtp, MTP_DEVICE_PROPERTY_BATTERY_LEVEL) as? Int ?: return null
            when {
                raw in 0..100 -> raw
                raw in 0..255 -> (raw * 100 / 255)
                else -> null
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
