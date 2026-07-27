package dev.packetins.studio.store

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.packetins.protocol.PacketInsProtocol
import dev.packetins.studio.model.InspectedExchange
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

@Serializable
data class SavedTrafficSession(
    val id: String,
    val name: String,
    val savedAtMs: Long,
    val exchanges: List<InspectedExchange>,
) {
    override fun toString(): String = "$name (${exchanges.size} items)"
}

class SavedTrafficStore(project: Project) {
    private val log = Logger.getInstance(SavedTrafficStore::class.java)
    private val directory = PathManager.getConfigDir()
        .resolve("oneinsight")
        .resolve("saved")
        .resolve(projectKey(project))

    fun save(name: String, exchanges: List<InspectedExchange>): SavedTrafficSession {
        Files.createDirectories(directory)
        val savedAt = System.currentTimeMillis()
        val session = SavedTrafficSession(
            id = "$savedAt-${UUID.randomUUID()}",
            name = name.trim().ifBlank { "Traffic $savedAt" },
            savedAtMs = savedAt,
            exchanges = exchanges,
        )
        write(session)
        return session
    }

    fun replace(session: SavedTrafficSession) {
        Files.createDirectories(directory)
        write(session)
    }

    private fun write(session: SavedTrafficSession) {
        val destination = pathFor(session.id)
        val temporary = destination.resolveSibling("${destination.fileName}.tmp")
        Files.writeString(
            temporary,
            PacketInsProtocol.json.encodeToString(session),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        runCatching {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun list(): List<SavedTrafficSession> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }
                .toList()
                .mapNotNull { path ->
                    runCatching {
                        PacketInsProtocol.json.decodeFromString<SavedTrafficSession>(Files.readString(path))
                    }.onFailure { log.warn("Unable to read saved OneInsight session: $path", it) }
                        .getOrNull()
                }
        }.sortedByDescending { it.savedAtMs }
    }

    fun delete(session: SavedTrafficSession) {
        runCatching { Files.deleteIfExists(pathFor(session.id)) }
            .onFailure { log.warn("Unable to delete saved OneInsight session ${session.id}", it) }
    }

    private fun pathFor(id: String) = directory.resolve("$id.json")

    private fun projectKey(project: Project): String {
        val name = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val pathHash = (project.basePath ?: project.name).hashCode().toUInt().toString(16)
        return "$name-$pathHash"
    }
}
