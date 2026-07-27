package dev.packetins.sample

import android.app.Application
import dev.packetins.sdk.PacketIns
import dev.packetins.sdk.PacketInsConfig

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PacketIns.start(
            this,
            PacketInsConfig(
                host = "127.0.0.1",
                port = 54789,
            ),
        )
    }
}
