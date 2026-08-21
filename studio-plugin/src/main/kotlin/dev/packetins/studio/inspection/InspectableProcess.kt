package dev.packetins.studio.inspection

data class InspectableProcess(
    val packageName: String,
    val processName: String,
    val pid: Int,
    val deviceSerial: String,
) {
    val stableId: String = "$deviceSerial:$pid"

    override fun toString(): String {
        val suffix = if (processName != packageName) " — $processName" else ""
        return "$packageName$suffix (pid $pid, $deviceSerial)"
    }
}
