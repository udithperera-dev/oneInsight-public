package dev.packetins.studio

import com.android.tools.idea.appinspection.api.process.ProcessesModel
import com.android.tools.idea.appinspection.ide.AppInspectionDiscoveryService
import com.android.tools.idea.appinspection.inspector.api.AppInspectorJar
import com.android.tools.idea.appinspection.inspector.api.AppInspectorMessenger
import com.android.tools.idea.appinspection.inspector.api.launch.LaunchParameters
import com.android.tools.idea.appinspection.inspectors.network.model.NetworkInspectorClientImpl
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PropertiesComponent
import dev.packetins.studio.inspection.InspectableProcess
import dev.packetins.studio.inspection.StudioNetworkEventMapper
import dev.packetins.studio.store.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class PacketInsProjectService(private val project: Project) : Disposable {
    val store = EventStore()

    private val log = Logger.getInstance(PacketInsProjectService::class.java)
    private val discoveryService by lazy { AppInspectionDiscoveryService.instance }
    private val apiServices by lazy { discoveryService.apiServices }
    private val processesModel by lazy {
        ProcessesModel(apiServices.processDiscovery) { descriptor -> descriptor.isRunning }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val healthListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var inspectionJob: Job? = null
    private var autoAttachJob: Job? = null
    private var messenger: AppInspectorMessenger? = null
    private var selected: InspectableProcess? = null
    private val capturedEvents = AtomicLong()
    private var activeHooks: List<String> = emptyList()
    private var preferredPackage: String? =
        PropertiesComponent.getInstance(project).getValue("packetins.lastPackage")
    @Volatile
    private var maxBodyBytes: Int = 5 * 1024 * 1024

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

    fun listProcesses(): List<InspectableProcess> =
        runCatching {
            processesModel.processes
                .asSequence()
                .filter { it.isRunning && it.pid > 0 && it.packageName.isNotBlank() }
                .map {
                    InspectableProcess(
                        packageName = it.packageName,
                        processName = it.name,
                        pid = it.pid,
                        deviceSerial = it.device.serial,
                        descriptor = it,
                    )
                }
                .sortedWith(compareBy({ it.packageName }, { it.processName }))
                .toList()
        }.getOrElse { error ->
            log.warn("Failed to list App Inspection processes", error)
            updateStatus("Process discovery failed: ${error.message ?: error.javaClass.simpleName}")
            emptyList()
        }

    fun selectedProcess(): InspectableProcess? = selected

    fun isServerRunning(): Boolean = inspectionJob?.isActive == true

    fun setMaxBodyBytes(value: Int) {
        maxBodyBytes = value.coerceIn(256 * 1024, 20 * 1024 * 1024)
    }

    fun startListening() {
        updateStatus("Process discovery active. Run a debuggable app, refresh, then Attach.")
    }

    fun stopListening() {
        inspectionJob?.cancel()
        inspectionJob = null
        val current = selected
        messenger = null
        selected = null
        activeHooks = emptyList()
        updateHealth("Capture idle")
        if (current != null) {
            scope.launch {
                try {
                    apiServices.stopInspectors(current.descriptor)
                } catch (_: Throwable) {
                    // The process may already have exited.
                }
            }
        }
        updateStatus("Detached")
    }

    fun attachToProcess(process: InspectableProcess) {
        if (inspectionJob?.isActive == true && selected?.stableId == process.stableId) return
        inspectionJob?.cancel()
        inspectionJob = scope.launch {
            try {
                preferredPackage = process.packageName
                PropertiesComponent.getInstance(project).setValue("packetins.lastPackage", process.packageName)
                updateStatus("Attaching Android Studio Network Inspector to ${process.packageName}…")
                clearLegacyDeviceProxy(process.deviceSerial)

                val inspectorJar = AppInspectorJar(
                    "network-inspector.jar",
                    "plugins/android/resources/app-inspection/",
                    "bazel-bin/tools/base/app-inspection/inspectors/network",
                )
                val launchedMessenger = apiServices.launchInspector(
                    LaunchParameters(
                        process.descriptor,
                        NETWORK_INSPECTOR_ID,
                        inspectorJar,
                        project.name,
                        null,
                        true,
                    )
                )
                messenger = launchedMessenger
                selected = process

                val startResponse = NetworkInspectorClientImpl(launchedMessenger).startInspection()
                val hooks = buildList {
                    if (startResponse.okhttpHooksRegistered) add("OkHttp")
                    if (startResponse.javaNetHooksRegistered) add("HttpURLConnection")
                    if (startResponse.grpcHooksRegistered) add("gRPC")
                }
                activeHooks = hooks
                capturedEvents.set(0)
                updateHealth(
                    if (hooks.isEmpty()) "Warning: attached, but no supported hooks detected"
                    else "Healthy · ${hooks.joinToString()} · 0 events"
                )
                updateStatus(
                    if (hooks.isEmpty()) {
                        "Attached to ${process.packageName}, but no supported network library was detected."
                    } else {
                        "Attached to ${process.packageName} using ${hooks.joinToString()} hooks — no VPN or proxy."
                    }
                )
                notify("OneInsight attached to ${process.packageName}", NotificationType.INFORMATION)

                val mapper = StudioNetworkEventMapper(
                    packageName = { selected?.packageName },
                    processId = { selected?.pid },
                    maxBodyBytes = maxBodyBytes,
                )
                launchedMessenger.eventFlow.collect { rawEvent: ByteArray ->
                    mapper.map(rawEvent).forEach {
                        store.accept(it)
                        val count = capturedEvents.incrementAndGet()
                        updateHealth("Healthy · ${activeHooks.joinToString()} · $count events · last event now")
                    }
                }
            } catch (t: Throwable) {
                log.warn("OneInsight attach failed for ${process.packageName}", t)
                inspectionJob = null
                messenger = null
                selected = null
                updateHealth("Capture failed · ${t.message ?: t.javaClass.simpleName}")
                updateStatus("Attach failed: ${t.message ?: t.javaClass.simpleName}")
                notify(
                    "OneInsight could not attach: ${t.message ?: t.javaClass.simpleName}",
                    NotificationType.ERROR,
                )
            }
        }
    }

    fun refreshAndAutoConnect() {
        val processes = listProcesses()
        val preferred = selected?.let { current ->
            processes.firstOrNull { it.stableId == current.stableId }
        } ?: processes.firstOrNull()
        if (preferred == null) {
            updateStatus("No inspectable process. Run a debug build, then click Refresh Processes.")
        } else {
            attachToProcess(preferred)
        }
    }

    fun setAutoAttach(enabled: Boolean) {
        if (!enabled) {
            autoAttachJob?.cancel()
            autoAttachJob = null
            return
        }
        if (autoAttachJob?.isActive == true) return
        autoAttachJob = scope.launch {
            while (true) {
                if (inspectionJob?.isActive != true) {
                    val processes = listProcesses()
                    val process = processes.firstOrNull { it.packageName == preferredPackage } ?: processes.firstOrNull()
                    if (process != null) attachToProcess(process)
                }
                delay(2_500)
            }
        }
    }

    private fun clearLegacyDeviceProxy(serial: String) {
        val adb = sequenceOf(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            "${System.getProperty("user.home")}/Library/Android/sdk",
            "${System.getProperty("user.home")}/Android/Sdk",
        ).filterNotNull()
            .map { File(it, "platform-tools/adb") }
            .firstOrNull { it.isFile } ?: return
        runCatching {
            ProcessBuilder(
                adb.absolutePath,
                "-s",
                serial,
                "shell",
                "settings",
                "put",
                "global",
                "http_proxy",
                ":0",
            ).start().waitFor()
        }
    }

    private fun updateStatus(text: String) {
        statusText = text
        statusListeners.forEach { it(text) }
    }

    private fun updateHealth(text: String) {
        healthText = text
        healthListeners.forEach { it(text) }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OneInsight")
            .createNotification(content, type)
            .notify(project)
    }

    override fun dispose() {
        stopListening()
        runCatching { processesModel.dispose() }
        scope.cancel()
        statusListeners.clear()
        healthListeners.clear()
    }

    companion object {
        private const val NETWORK_INSPECTOR_ID = "studio.network.inspection"
    }
}
