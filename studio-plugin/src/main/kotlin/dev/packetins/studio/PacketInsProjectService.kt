package dev.packetins.studio

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PropertiesComponent
import dev.packetins.studio.inspection.InspectableProcess
import dev.packetins.studio.store.EventStore
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class PacketInsProjectService(private val project: Project) : Disposable {
    val store = EventStore()

    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val healthListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var selected: InspectableProcess? = null
    private var preferredPackage: String? =
        PropertiesComponent.getInstance(project).getValue("packetins.lastPackage")

    @Volatile
    var statusText: String = "Select a debuggable process"
        private set

    @Volatile
    var healthText: String = "Capture idle"
        private set

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners += listener
        listener(statusText)
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners -= listener
    }

    fun addHealthListener(listener: (String) -> Unit) {
        healthListeners += listener
        listener(healthText)
    }

    fun removeHealthListener(listener: (String) -> Unit) {
        healthListeners -= listener
    }

    fun listProcesses(): List<InspectableProcess> = emptyList()

    fun attach(process: InspectableProcess) {
        selected = process
        preferredPackage = process.packageName
        PropertiesComponent.getInstance(project).setValue("packetins.lastPackage", process.packageName)
        updateStatus("Attached to ${process.packageName}")
        updateHealth("Listening for network events")
    }

    fun detach() {
        selected = null
        store.clear()
        updateStatus("Select a debuggable process")
        updateHealth("Capture idle")
    }

    fun selectedProcess(): InspectableProcess? = selected

    fun preferredPackageName(): String? = preferredPackage

    fun setMaxBodyBytes(bytes: Int) {
        // Retained for tool-window settings compatibility.
    }

    private fun updateStatus(value: String) {
        statusText = value
        statusListeners.forEach { it(value) }
    }

    private fun updateHealth(value: String) {
        healthText = value
        healthListeners.forEach { it(value) }
    }

    override fun dispose() {
        detach()
        statusListeners.clear()
        healthListeners.clear()
    }
}
