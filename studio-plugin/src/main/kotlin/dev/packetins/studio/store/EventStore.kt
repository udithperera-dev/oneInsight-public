package dev.packetins.studio.store

import dev.packetins.protocol.EventType
import dev.packetins.protocol.PacketInsEvent
import dev.packetins.protocol.PacketInsProtocol
import dev.packetins.studio.model.ExchangeKind
import dev.packetins.studio.model.InspectedExchange
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class EventStore(
    private val maxEvents: Int = PacketInsProtocol.DEFAULT_MAX_EVENTS,
) {
    private val exchanges = linkedMapOf<String, InspectedExchange>()
    private val listeners = CopyOnWriteArrayList<(List<InspectedExchange>) -> Unit>()
    private val paused = AtomicBoolean(false)

    @Synchronized
    fun accept(event: PacketInsEvent) {
        if (paused.get()) return
        if (event.type == EventType.HEARTBEAT) return

        val existing = exchanges[event.correlationId]
        val updated = if (existing == null) {
            InspectedExchange.fromEvent(event)
        } else {
            existing.merge(event)
        }
        exchanges.remove(event.correlationId)
        exchanges[event.correlationId] = updated

        while (exchanges.size > maxEvents) {
            val firstKey = exchanges.keys.firstOrNull() ?: break
            exchanges.remove(firstKey)
        }
        notifyListeners()
    }

    @Synchronized
    fun clear() {
        exchanges.clear()
        notifyListeners()
    }

    fun setPaused(value: Boolean) {
        paused.set(value)
    }

    fun isPaused(): Boolean = paused.get()

    @Synchronized
    fun snapshot(): List<InspectedExchange> =
        exchanges.values.sortedWith(
            compareByDescending<InspectedExchange> { it.pinned }
                .thenByDescending { it.updatedAtMs }
        )

    @Synchronized
    fun updateMetadata(
        correlationId: String,
        pinned: Boolean? = null,
        tags: List<String>? = null,
        note: String? = null,
        updateNote: Boolean = false,
    ) {
        val current = exchanges[correlationId] ?: return
        exchanges[correlationId] = current.copy(
            pinned = pinned ?: current.pinned,
            tags = tags ?: current.tags,
            note = if (updateNote) note else current.note,
        )
        notifyListeners()
    }

    fun addListener(listener: (List<InspectedExchange>) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun removeListener(listener: (List<InspectedExchange>) -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        val data = snapshot()
        listeners.forEach { it(data) }
    }
}

data class EventFilter(
    val query: String = "",
    val httpOnly: Boolean = false,
    val websocketOnly: Boolean = false,
    val sseOnly: Boolean = false,
    val grpcOnly: Boolean = false,
    val errorsOnly: Boolean = false,
    val host: String = "",
    val statusMin: Int? = null,
    val statusMax: Int? = null,
    val durationMinMs: Long? = null,
    val durationMaxMs: Long? = null,
    val queryIsRegex: Boolean = false,
) {
    fun matches(exchange: InspectedExchange): Boolean {
        val selectedKinds = buildSet {
            if (httpOnly) add(ExchangeKind.HTTP)
            if (websocketOnly) add(ExchangeKind.WEBSOCKET)
            if (sseOnly) add(ExchangeKind.SSE)
            if (grpcOnly) add(ExchangeKind.GRPC)
        }
        if (selectedKinds.isNotEmpty() && exchange.kind !in selectedKinds) return false
        if (errorsOnly && exchange.errorMessage == null && (exchange.statusCode == null || exchange.statusCode < 400)) {
            return false
        }
        if (host.isNotBlank() && !exchange.host.contains(host, ignoreCase = true)) return false
        if (statusMin != null && (exchange.statusCode == null || exchange.statusCode < statusMin)) return false
        if (statusMax != null && (exchange.statusCode == null || exchange.statusCode > statusMax)) return false
        if (durationMinMs != null && (exchange.durationMs == null || exchange.durationMs < durationMinMs)) return false
        if (durationMaxMs != null && (exchange.durationMs == null || exchange.durationMs > durationMaxMs)) return false
        if (query.isBlank()) return true
        val values = listOfNotNull(
            exchange.method,
            exchange.host,
            exchange.path,
            exchange.url,
            exchange.statusCode?.toString(),
            exchange.packageName,
            exchange.errorMessage,
        )
        return if (queryIsRegex) {
            runCatching { Regex(query, RegexOption.IGNORE_CASE) }
                .getOrNull()
                ?.let { regex -> values.any(regex::containsMatchIn) }
                ?: false
        } else {
            val q = query.lowercase()
            values.any { it.lowercase().contains(q) }
        }
    }
}
