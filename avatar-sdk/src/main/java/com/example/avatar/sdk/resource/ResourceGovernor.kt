package com.example.avatar.sdk.resource

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.StatFs

enum class ResourceState { NORMAL, LOW_MEMORY, LOW_STORAGE, THERMAL_LIMIT, GENERATION_DISABLED, RENDER_ONLY }
data class ResourceSnapshot(
    val state: ResourceState,
    val availableRamBytes: Long,
    val availableStorageBytes: Long,
    val lowMemory: Boolean,
    val cpuCores: Int,
    val thermalStatus: Int?
)

class ResourceGovernor(private val context: Context) {
    fun snapshot(): ResourceSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val storage = StatFs(context.filesDir.absolutePath).availableBytes
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java)?.currentThermalStatus else null
        val state = when {
            info.lowMemory -> ResourceState.LOW_MEMORY
            storage < 512L * 1024 * 1024 -> ResourceState.LOW_STORAGE
            thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE -> ResourceState.THERMAL_LIMIT
            else -> ResourceState.NORMAL
        }
        return ResourceSnapshot(state, info.availMem, storage, info.lowMemory, Runtime.getRuntime().availableProcessors(), thermal)
    }
}
