package dev.packetins.studio.store

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.packetins.protocol.PacketInsProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

@Serializable
data class SavedReplayRequest(
    val id: String,
    val name: String,
    val method: String,
    val url: String,
    val headersText: String,
    val bodyText: String,
    val updatedAtMs: Long,
) {
    override fun toString(): String = "$name  ·  $method"
}

class ReplayRequestStore(project: Project) {
    private val log = Logger.getInstance(ReplayRequestStore::class.java)
    private val directory = PathManager.getConfigDir()
        .resolve("oneinsight")
        .resolve("replay")
        .resolve(projectKey(project))

    fun save(
        existingId: String?,
        name: String,
        method: String,
        url: String,
        headersText: String,
        bodyText: String,
    ): SavedReplayRequest {
        Files.createDirectories(directory)
        val request = SavedReplayRequest(
            id = existingId ?: "${System.currentTimeMillis()}-${UUID.randomUUID()}",
            name = name.trim().ifBlank { "$method request" },
            method = method,
            url = url,
            headersText = headersText,
            bodyText = bodyText,
            updatedAtMs = System.currentTimeMillis(),
        )
        write(request)
        return request
    }

    fun list(): List<SavedReplayRequest> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }
                .toList()
                .mapNotNull { path ->
                    runCatching {
                        PacketInsProtocol.json.decodeFromString<SavedReplayRequest>(Files.readString(path))
                    }.onFailure { log.warn("Unable to read saved OneInsight replay request: $path", it) }
                        .getOrNull()
                }
        }.sortedByDescending { it.updatedAtMs }
    }

    fun delete(request: SavedReplayRequest) {
        runCatching { Files.deleteIfExists(pathFor(request.id)) }
            .onFailure { log.warn("Unable to delete saved OneInsight replay request ${request.id}", it) }
    }

    private fun write(request: SavedReplayRequest) {
        val destination = pathFor(request.id)
        val temporary = destination.resolveSibling("${destination.fileName}.tmp")
        Files.writeString(
            temporary,
            PacketInsProtocol.json.encodeToString(request),
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

    private fun pathFor(id: String) = directory.resolve("$id.json")

    private fun projectKey(project: Project): String {
        val name = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val pathHash = (project.basePath ?: project.name).hashCode().toUInt().toString(16)
        return "$name-$pathHash"
    }
}
