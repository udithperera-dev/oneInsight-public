package dev.packetins.studio.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.packetins.studio.PacketInsProjectService
import dev.packetins.studio.inspection.InspectableProcess
import dev.packetins.studio.model.InspectedExchange
import dev.packetins.studio.share.BodyPreviewDecoder
import dev.packetins.studio.share.TrafficShare
import dev.packetins.studio.store.EventFilter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class PacketInsToolWindowPanel(project: Project) : SimpleToolWindowPanel(false, true), Disposable {
    private val service = project.getService(PacketInsProjectService::class.java)
    private val processModel = DefaultComboBoxModel<InspectableProcess>()
    private val processCombo = JComboBox(processModel)
    private val statusLabel = JBLabel(service.statusText)
    private val healthLabel = JBLabel(service.healthText)
    private val filterField = JBTextField()
    private val tableModel = ExchangeTableModel()
    private val table = JBTable(tableModel)
    private val overviewArea = JTextArea()
    private val requestArea = JTextArea()
    private val responseArea = JTextArea()
    private var exchanges: List<InspectedExchange> = emptyList()
    private var filter = EventFilter()

    private val statusListener: (String) -> Unit = { text ->
        ApplicationManager.getApplication().invokeLater { statusLabel.text = text }
    }
    private val healthListener: (String) -> Unit = { text ->
        ApplicationManager.getApplication().invokeLater { healthLabel.text = text }
    }
    private val storeListener: (List<InspectedExchange>) -> Unit = { items ->
        ApplicationManager.getApplication().invokeLater {
            exchanges = items
            refreshTable()
        }
    }

    init {
        toolbar = buildToolbar()
        setContent(buildContent())
        configureEditors()
        service.addStatusListener(statusListener)
        service.addHealthListener(healthListener)
        service.store.addListener(storeListener)
        refreshProcesses()
    }

    private fun buildToolbar(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val refresh = JButton("Refresh Processes").apply { addActionListener { refreshProcesses() } }
        val attach = JButton("Attach").apply {
            addActionListener {
                val selected = processCombo.selectedItem as? InspectableProcess
                if (selected == null) {
                    Messages.showInfoMessage(
                        "No inspectable process is available yet. Start a debuggable app and refresh.",
                        "OneInsight",
                    )
                } else {
                    service.attach(selected)
                }
            }
        }
        val detach = JButton("Detach").apply { addActionListener { service.detach() } }
        val clear = JButton("Clear").apply { addActionListener { service.store.clear() } }
        val copyCurl = JButton("Copy cURL").apply {
            addActionListener {
                val exchange = selectedExchange() ?: return@addActionListener
                CopyPasteManager.getInstance().setContents(StringSelection(TrafficShare.curl(exchange)))
            }
        }
        filterField.emptyText.text = "Filter method, host, path…"
        filterField.preferredSize = Dimension(220, filterField.preferredSize.height)
        filterField.addActionListener {
            filter = filter.copy(query = filterField.text.trim())
            refreshTable()
        }
        panel.add(refresh)
        panel.add(processCombo)
        panel.add(attach)
        panel.add(detach)
        panel.add(clear)
        panel.add(copyCurl)
        panel.add(filterField)
        return panel
    }

    private fun buildContent(): JPanel {
        val root = JPanel(BorderLayout())
        val meta = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2)).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel)
            add(healthLabel)
        }

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) showSelected()
        }

        val details = JTabbedPane().apply {
            addTab("Overview", JBScrollPane(overviewArea))
            addTab("Request", JBScrollPane(requestArea))
            addTab("Response", JBScrollPane(responseArea))
        }

        val splitter = JBSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = details
        }

        root.add(meta, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        return root
    }

    private fun configureEditors() {
        listOf(overviewArea, requestArea, responseArea).forEach { area ->
            area.isEditable = false
            area.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            area.lineWrap = true
            area.wrapStyleWord = true
        }
    }

    private fun refreshProcesses() {
        val processes = service.listProcesses()
        processModel.removeAllElements()
        processes.forEach(processModel::addElement)
        val preferred = service.preferredPackageName()
        if (preferred != null) {
            val match = processes.firstOrNull { it.packageName == preferred }
            if (match != null) processCombo.selectedItem = match
        }
        if (processes.isEmpty()) {
            statusLabel.text = "No processes visible to App Inspection"
        }
    }

    private fun refreshTable() {
        val filtered = exchanges.filter(filter::matches)
        tableModel.setItems(filtered)
        if (filtered.isEmpty()) {
            overviewArea.text = ""
            requestArea.text = ""
            responseArea.text = ""
        } else if (table.selectedRow < 0) {
            table.setRowSelectionInterval(0, 0)
        } else {
            showSelected()
        }
    }

    private fun selectedExchange(): InspectedExchange? {
        val row = table.selectedRow
        if (row < 0) return null
        return tableModel.itemAt(row)
    }

    private fun showSelected() {
        val exchange = selectedExchange() ?: return
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(exchange.startedAtMs))
        overviewArea.text = buildString {
            appendLine("${exchange.method} ${exchange.url}")
            appendLine("Status: ${exchange.displayStatus}")
            appendLine("Duration: ${exchange.durationMs ?: "—"} ms")
            appendLine("Started: $time")
            appendLine("Package: ${exchange.packageName ?: "—"}")
            appendLine("Kind: ${exchange.kind}")
            if (exchange.errorMessage != null) appendLine("Error: ${exchange.errorMessage}")
        }
        requestArea.text = buildString {
            appendLine("Headers")
            exchange.requestHeaders.forEach { appendLine("${it.name}: ${it.value}") }
            appendLine()
            appendLine("Body")
            append(BodyPreviewDecoder.preview(exchange.requestBody))
        }
        responseArea.text = buildString {
            appendLine("Headers")
            exchange.responseHeaders.forEach { appendLine("${it.name}: ${it.value}") }
            appendLine()
            appendLine("Body")
            append(BodyPreviewDecoder.preview(exchange.responseBody))
        }
    }

    override fun dispose() {
        service.removeStatusListener(statusListener)
        service.removeHealthListener(healthListener)
        service.store.removeListener(storeListener)
    }

    private class ExchangeTableModel : AbstractTableModel() {
        private val columns = arrayOf("Time", "Method", "Status", "Host", "Path", "ms")
        private var items: List<InspectedExchange> = emptyList()
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun setItems(value: List<InspectedExchange>) {
            items = value
            fireTableDataChanged()
        }

        fun itemAt(row: Int): InspectedExchange? = items.getOrNull(row)

        override fun getRowCount(): Int = items.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = items[rowIndex]
            return when (columnIndex) {
                0 -> timeFormat.format(Date(item.startedAtMs))
                1 -> item.method
                2 -> item.displayStatus
                3 -> item.host
                4 -> item.path
                5 -> item.durationMs?.toString() ?: "—"
                else -> ""
            }
        }
    }
}
