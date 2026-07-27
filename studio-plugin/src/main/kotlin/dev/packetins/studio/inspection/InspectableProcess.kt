package dev.packetins.studio.inspection

import com.android.tools.idea.appinspection.inspector.api.process.ProcessDescriptor

class InspectableProcess internal constructor(
    val packageName: String,
    val processName: String,
    val pid: Int,
    val deviceSerial: String,
    internal val descriptor: ProcessDescriptor,
) {
    val stableId: String = "$deviceSerial:$pid"

    override fun toString(): String {
        val suffix = if (processName != packageName) " — $processName" else ""
        return "$packageName$suffix (pid $pid, $deviceSerial)"
    }
}
