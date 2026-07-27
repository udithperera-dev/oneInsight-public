package dev.packetins.studio.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.packetins.protocol.PacketInsProtocol
import dev.packetins.studio.PacketInsProjectService
import dev.packetins.studio.inspection.InspectableProcess
import dev.packetins.studio.model.ExchangeKind
import dev.packetins.studio.model.InspectedExchange
import dev.packetins.studio.share.BodyPreviewDecoder
import dev.packetins.studio.share.TrafficShare
import dev.packetins.studio.store.EventFilter
import dev.packetins.studio.store.ReplayRequestStore
import dev.packetins.studio.store.SavedReplayRequest
import dev.packetins.studio.store.SavedTrafficSession
import dev.packetins.studio.store.SavedTrafficStore
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.awt.event.ActionEvent
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.text.DefaultHighlighter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min

class PacketInsToolWindowPanel(
    private val project: Project,
) : SimpleToolWindowPanel(false, true), Disposable {
    private val service = project.getService(PacketInsProjectService::class.java)
    private val savedTrafficStore = SavedTrafficStore(project)
    private val replayRequestStore = ReplayRequestStore(project)
    private val properties = PropertiesComponent.getInstance(project)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val processModel = DefaultComboBoxModel<InspectableProcess>()
    private val processCombo = JComboBox(processModel)
    private val statusLabel = JBLabel("Idle")
    private val healthLabel = JBLabel("Capture idle")
    private val autoAttach = JCheckBox("Auto-attach", true)
    private val bodyLimitCombo = JComboBox(arrayOf("1 MB", "5 MB", "10 MB", "20 MB")).apply {
        selectedIndex = 1
    }
    private val searchField = JBTextField().apply {
        emptyText.text = "Method, host, path or status"
    }
    private val httpOnly = JCheckBox("HTTP")
    private val wsOnly = JCheckBox("WS")
    private val sseOnly = JCheckBox("SSE")
    private val grpcOnly = JCheckBox("gRPC")
    private val errorsOnly = JCheckBox("Errors")
    private val advancedFilterButton = JButton("Filter")
    private val advancedFilterLabel = JBLabel("")
    private val pauseButton = JButton("Pause")
    private val clearButton = JButton("Clear")
    private val refreshButton = JButton("Refresh")
    private val listenButton = JButton("Detach")
    private val connectButton = JButton("Attach")

    private val tableModel = ExchangeTableModel(selectable = true)
    private val table = JBTable(tableModel).apply {
        configureTrafficTable(this, selectable = true)
    }
    private val savedTableModel = ExchangeTableModel(selectable = false)
    private val savedTable = JBTable(savedTableModel).apply {
        configureTrafficTable(this, selectable = false)
    }
    private val liveColumns = (0 until table.columnModel.columnCount)
        .associate { table.columnModel.getColumn(it).modelIndex to table.columnModel.getColumn(it) }
    private val workspaceTabs = JBTabbedPane()
    private val trafficTabs = JBTabbedPane()
    private val timelinePanel = TrafficTimelinePanel()
    private val savedSessionModel = DefaultComboBoxModel<SavedTrafficSession>()
    private val savedSessionCombo = JComboBox(savedSessionModel).apply {
        preferredSize = Dimension(JBUI.scale(310), preferredSize.height)
    }
    private val selectAllButton = JButton("All")
    private val clearSelectionButton = JButton("None")
    private val saveSnapshotButton = JButton("Save")
    private val columnsButton = JButton("Columns…")
    private val exportButton = JButton("Export")
    private val importButton = JButton("Import")
    private val redactExport = JCheckBox("Redact", true)
    private val selectedCountLabel = JBLabel("0 selected")
    private val deleteSavedButton = JButton("Delete").apply { isEnabled = false }
    private val compareButton = JButton("Compare").apply { isEnabled = false }
    private val exportDiffButton = JButton("Diff").apply { isEnabled = false }
    private val detailArea = JEditorPane("text/html", "").apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        border = JBUI.Borders.empty(8)
    }
    private val requestArea = createReadOnlyHtmlPane()
    private val responseArea = createReadOnlyHtmlPane()
    private val timingArea = createReadOnlyHtmlPane()
    private val detailSearchField = JBTextField().apply {
        emptyText.text = "Headers or body text"
        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
    }
    private val detailSearchStatus = JBLabel("")
    private val copyCurlButton = JButton("cURL").apply { isEnabled = false }
    private val copyJsonButton = JButton("JSON").apply { isEnabled = false }
    private val copyFetchButton = JButton("Fetch").apply { isEnabled = false }
    private val copyOkHttpButton = JButton("OkHttp").apply { isEnabled = false }
    private val copyRetrofitButton = JButton("Retrofit").apply { isEnabled = false }
    private val replayButton = JButton("Replay").apply { isEnabled = false }
    private val replayMethodCombo = JComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")).apply {
        preferredSize = Dimension(JBUI.scale(105), preferredSize.height)
    }
    private val replaySavedModel = DefaultListModel<SavedReplayRequest>()
    private val replaySavedList = JBList(replaySavedModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No saved requests"
    }
    private val replayNewButton = JButton("New")
    private val replaySaveButton = JButton("Save")
    private val replaySaveAsButton = JButton("Save As")
    private val replayDeleteButton = JButton("Delete").apply { isEnabled = false }
    private val replayUrlField = JBTextField().apply {
        emptyText.text = "https://api.example.com/path"
        preferredSize = Dimension(JBUI.scale(580), preferredSize.height)
    }
    private val replaySendButton = JButton("Send")
    private val replayHeadersArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        lineWrap = false
    }
    private val replayBodyArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        lineWrap = true
        wrapStyleWord = true
    }
    private val replayResponseHeadersArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
    }
    private val replayResponseBodyArea = createReadOnlyHtmlPane()
    private val replayResponseStatus = JBLabel("Ready")
    private var activeReplayId: String? = null
    private val pinButton = JButton("Pin").apply { isEnabled = false }
    private val metadataButton = JButton("Metadata").apply { isEnabled = false }
    private val openDetailButton = JButton("Open").apply { isEnabled = false }
    private val expandJsonButton = JButton("Expand")
    private val collapseJsonButton = JButton("Collapse")
    private val jsonTree = JTree(DefaultMutableTreeNode("No JSON selected")).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = JBUI.scale(22)
    }
    private val previewHtmlPane = createReadOnlyHtmlPane()
    private val previewImageLabel = JLabel("", SwingConstants.CENTER).apply {
        horizontalTextPosition = SwingConstants.CENTER
        verticalTextPosition = SwingConstants.BOTTOM
        border = JBUI.Borders.empty(12)
    }
    private val previewEmptyLabel = JBLabel(
        "<html><div style='padding:16px'>No HTML or image preview for this response.<br>" +
            "Preview appears when the URL or Content-Type indicates HTML or an image.</div></html>",
        SwingConstants.CENTER,
    )
    private val previewCards = CardLayout()
    private val previewPanel = JPanel(previewCards).apply {
        add(JBScrollPane(previewEmptyLabel), "empty")
        add(JBScrollPane(previewHtmlPane), "html")
        add(JBScrollPane(previewImageLabel), "image")
    }
    private val detailTabs = JBTabbedPane().apply {
        addTab("Formatted", JBScrollPane(detailArea))
        addTab("Request", JBScrollPane(requestArea))
        addTab("Response", JBScrollPane(responseArea))
        addTab("Timing", JBScrollPane(timingArea))
        addTab("JSON Tree", buildJsonTreePanel(jsonTree, expandJsonButton, collapseJsonButton))
        addTab("Preview", previewPanel)
    }

    private var allExchanges: List<InspectedExchange> = emptyList()
    private var filter = EventFilter()

    private val storeListener: (List<InspectedExchange>) -> Unit = { exchanges ->
        ApplicationManager.getApplication().invokeLater {
            allExchanges = exchanges
            applyFilter()
        }
    }

    private val statusListener: (String) -> Unit = { text ->
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = text
            statusLabel.toolTipText = "Capture status: $text"
            listenButton.isEnabled = service.isServerRunning()
        }
    }
    private val healthListener: (String) -> Unit = { text ->
        ApplicationManager.getApplication().invokeLater {
            healthLabel.text = "● $text"
            healthLabel.toolTipText = "Capture health: $text"
            healthLabel.foreground = when {
                text.contains("healthy", ignoreCase = true) -> HEALTHY_FG
                text.contains("warning", ignoreCase = true) ||
                    text.contains("idle", ignoreCase = true) -> WARNING_FG
                else -> ERROR_FG
            }
        }
    }

    init {
        toolbar = null
        setContent(buildContent())
        applyUxStyles()
        wireEvents()
        restorePreferences()
        service.store.addListener(storeListener)
        service.addStatusListener(statusListener)
        service.addHealthListener(healthListener)
        refreshProcesses()
        reloadSavedSessions()
        reloadReplayRequests()
        newReplayRequest()
        updateSelectedCount()
        service.startListening()
        service.setAutoAttach(true)
        if (processModel.size == 0) {
            statusLabel.text = "No inspectable process. Run a debug build, then click Refresh Processes."
        }
    }

    private fun buildContent(): JPanel {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        processCombo.preferredSize = Dimension(390, processCombo.preferredSize.height)
        searchField.preferredSize = Dimension(220, searchField.preferredSize.height)
        controls.add(JBLabel("Debug process:").apply {
            toolTipText = "Choose the running debug app process to inspect"
        })
        controls.add(processCombo)
        controls.add(refreshButton)
        controls.add(connectButton)
        controls.add(autoAttach)
        controls.add(listenButton)
        controls.add(JBLabel("Body limit:").apply {
            toolTipText = "Stop storing body bytes after this size to keep capture responsive"
        })
        controls.add(bodyLimitCombo)

        val help = JBLabel(
            "<html><b>OneInsight Network Inspector</b> — Run your debug app, select its process, then use " +
                "<b>Attach</b> or <b>Auto-attach</b> to begin recording.<br>" +
                "Choose a recorded row to inspect headers, bodies, JSON, timing, and previews. " +
                "Check rows to save or export them, or use <b>Open in Replay</b> to edit and resend a request.<br>" +
                "<span style='color:#47C95E'>■ HTTP</span> &nbsp; " +
                "<span style='color:#7A3E9D'>■ WebSocket</span> &nbsp; " +
                "<span style='color:#00897B'>■ SSE</span> &nbsp; " +
                "<span style='color:#5E35B1'>■ gRPC</span> &nbsp; " +
                "<span style='color:#B3261E'>■ Error</span></html>",
        ).apply {
            border = JBUI.Borders.empty(4, 0, 8, 0)
            toolTipText = "Quick start: run a debug build → Refresh → Attach. Row colors: green HTTP, purple WebSocket, teal SSE, violet gRPC, red errors"
        }

        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(statusLabel, BorderLayout.CENTER)
            add(healthLabel, BorderLayout.EAST)
        }

        val top = JPanel(BorderLayout())
        top.add(help, BorderLayout.NORTH)
        top.add(controls, BorderLayout.CENTER)
        top.add(statusBar, BorderLayout.SOUTH)

        val detailSearchBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = DETAIL_HEADER_BG
            border = JBUI.Borders.empty(7, 8)
            add(JBLabel("<html><b>Traffic Details</b></html>").apply {
                toolTipText = "Inspect the selected traffic row: headers, bodies, timing, JSON, and preview"
            })
            add(JBLabel("Search selected request/response:").apply {
                toolTipText = "Find text inside the selected exchange"
            })
            add(detailSearchField)
            add(detailSearchStatus)
        }
        val detailCopyActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = true
                background = DETAIL_TOOLBAR_BG
                add(JBLabel("Copy as:").apply {
                    toolTipText = "Copy the selected request in a ready-to-paste format"
                })
                add(copyCurlButton)
                add(copyJsonButton)
                add(copyFetchButton)
                add(copyOkHttpButton)
                add(copyRetrofitButton)
        }
        val detailActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = true
                background = DETAIL_TOOLBAR_BG
                add(replayButton)
                add(pinButton)
                add(metadataButton)
                add(openDetailButton)
        }
        val detailToolbar = JPanel(GridLayout(0, 1, 0, JBUI.scale(4))).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(detailSearchBar)
            add(detailCopyActions)
            add(detailActions)
        }
        val detailPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(DETAIL_BORDER, 1)
            add(detailToolbar, BorderLayout.NORTH)
            add(detailTabs, BorderLayout.CENTER)
        }

        val recordedFilterBar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = RECORD_HEADER_BG
            toolTipText = "Toggle protocol/error filters. Leave all unchecked to show everything"
            add(httpOnly)
            add(wsOnly)
            add(sseOnly)
            add(grpcOnly)
            add(errorsOnly)
            add(advancedFilterButton)
            add(advancedFilterLabel)
        }
        val recordedSearchBar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            background = RECORD_HEADER_BG
            border = JBUI.Borders.empty(4, 8)
            add(JBLabel("<html><b>Recorded Traffic</b></html>").apply {
                toolTipText = "Live network traffic captured from the attached Android process"
            }, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(recordedFilterBar, BorderLayout.EAST)
        }
        val recordedCaptureActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = RECORD_TOOLBAR_BG
            add(pauseButton)
            add(clearButton)
            add(columnsButton)
        }
        val recordedSelectionActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = RECORD_TOOLBAR_BG
            add(selectAllButton)
            add(clearSelectionButton)
            add(saveSnapshotButton)
            add(exportButton)
            add(importButton)
            add(redactExport)
            add(selectedCountLabel)
        }
        val recordedActionsBar = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = RECORD_TOOLBAR_BG
            border = JBUI.Borders.empty(3, 8)
            add(recordedCaptureActions, BorderLayout.WEST)
            add(recordedSelectionActions, BorderLayout.EAST)
        }
        val livePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(RECORD_BORDER, 1)
            add(JPanel(GridLayout(2, 1, 0, JBUI.scale(2))).apply {
                add(recordedSearchBar)
                add(recordedActionsBar)
            }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        val savedPanel = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                add(JBLabel("Session:").apply {
                    toolTipText = "Saved traffic snapshot name and timestamp"
                })
                add(savedSessionCombo)
                add(compareButton)
                add(exportDiffButton)
                add(deleteSavedButton)
            }, BorderLayout.NORTH)
            add(JBScrollPane(savedTable), BorderLayout.CENTER)
        }
        trafficTabs.addTab("Live", livePanel)
        trafficTabs.addTab("Saved", savedPanel)
        trafficTabs.addTab("Timeline", JBScrollPane(timelinePanel))
        trafficTabs.setForegroundAt(0, HTTP_ACCENT)
        trafficTabs.setForegroundAt(1, HEALTHY_FG)
        trafficTabs.setForegroundAt(2, GRPC_ACCENT)

        // Left: recorded traffic and saved history. Right: details and JSON tools.
        val splitter = JBSplitter(false, 0.48f)
        splitter.firstComponent = trafficTabs
        splitter.secondComponent = detailPanel
        workspaceTabs.addTab("Traffic", splitter)
        workspaceTabs.addTab("Replay", buildReplayPanel())
        workspaceTabs.setForegroundAt(0, HTTP_ACCENT)
        workspaceTabs.setForegroundAt(1, HEALTHY_FG)

        root.add(top, BorderLayout.NORTH)
        root.add(workspaceTabs, BorderLayout.CENTER)
        return root
    }

    private fun buildReplayPanel(): JComponent {
        val requestTabs = JBTabbedPane().apply {
            addTab("Headers", JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6)
                add(JBLabel("One header per line: Name: value").apply {
                    toolTipText = "Example: Content-Type: application/json"
                }, BorderLayout.NORTH)
                add(JBScrollPane(replayHeadersArea), BorderLayout.CENTER)
            })
            addTab("Body", JBScrollPane(replayBodyArea))
        }
        val requestPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(RECORD_BORDER, 1)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(7))).apply {
                isOpaque = true
                background = RECORD_HEADER_BG
                add(JBLabel("<html><b>Request Builder</b></html>").apply {
                    toolTipText = "Compose method, URL, headers, and body, then click Send"
                })
                add(replayMethodCombo)
                add(replayUrlField)
                add(replaySendButton)
            }, BorderLayout.NORTH)
            add(requestTabs, BorderLayout.CENTER)
        }
        val responseTabs = JBTabbedPane().apply {
            addTab("Body", JBScrollPane(replayResponseBodyArea))
            addTab("Headers", JBScrollPane(replayResponseHeadersArea))
        }
        installColoredTabs(requestTabs, listOf(HTTP_ACCENT, HEALTHY_SOLID), listOf(
            "Edit request headers for Send",
            "Edit request body for Send",
        ))
        installColoredTabs(responseTabs, listOf(HEALTHY_SOLID, GRPC_ACCENT), listOf(
            "Body returned by the last Send",
            "Headers returned by the last Send",
        ))
        val responsePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(DETAIL_BORDER, 1)
            add(JPanel(BorderLayout()).apply {
                isOpaque = true
                background = DETAIL_HEADER_BG
                border = JBUI.Borders.empty(7, 8)
                add(JBLabel("<html><b>Response</b></html>").apply {
                    toolTipText = "Result of the last Replay Send from this IDE"
                }, BorderLayout.WEST)
                add(replayResponseStatus, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(responseTabs, BorderLayout.CENTER)
        }
        val editor = JBSplitter(true, 0.46f).apply {
            firstComponent = requestPanel
            secondComponent = responsePanel
        }
        val savedRequestsPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(245), JBUI.scale(500))
            border = JBUI.Borders.customLine(RECORD_BORDER, 1)
            add(JPanel(BorderLayout()).apply {
                isOpaque = true
                background = RECORD_HEADER_BG
                border = JBUI.Borders.empty(7, 8)
                add(JBLabel("<html><b>Saved Requests</b></html>").apply {
                    toolTipText = "Project-local Replay favorites — New / Save / Save As / Delete"
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(replaySavedList), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
                border = JBUI.Borders.empty(2)
                add(replayNewButton)
                add(replaySaveButton)
                add(replaySaveAsButton)
                add(replayDeleteButton)
            }, BorderLayout.SOUTH)
        }
        return JBSplitter(false, 0.22f).apply {
            firstComponent = savedRequestsPanel
            secondComponent = editor
        }
    }

    private fun applyUxStyles() {
        installColoredTabs(
            workspaceTabs,
            listOf(HTTP_ACCENT, HEALTHY_SOLID),
            listOf(
                "Inspect live, saved, and timeline network traffic",
                "Edit and resend captured HTTP requests like Postman",
            ),
        )
        installColoredTabs(
            trafficTabs,
            listOf(HTTP_ACCENT, HEALTHY_SOLID, GRPC_ACCENT),
            listOf(
                "Live traffic captured from the attached process",
                "Saved traffic snapshots for this project",
                "Timeline view of request timing and overlap",
            ),
        )
        installColoredTabs(
            detailTabs,
            listOf(HTTP_ACCENT, HEALTHY_SOLID, TEAL_ACCENT, ORANGE_ACCENT, GRPC_ACCENT, PREVIEW_ACCENT),
            listOf(
                "Readable formatted summary of the selected exchange",
                "Raw request headers and body",
                "Raw response headers and body",
                "Timing breakdown for the selected exchange",
                "Expandable JSON tree for request/response bodies",
                "HTML/image preview of the response when available",
            ),
        )

        styleFilledButton(connectButton, HEALTHY_SOLID)
        styleFilledButton(replaySendButton, HEALTHY_SOLID)
        styleFilledButton(replayButton, PREVIEW_ACCENT)
        styleFilledButton(saveSnapshotButton, HEALTHY_SOLID)
        styleFilledButton(replaySaveButton, HEALTHY_SOLID)
        styleFilledButton(replaySaveAsButton, HTTP_ACCENT)

        styleFilledButton(clearButton, DANGER_SOLID)
        styleFilledButton(deleteSavedButton, DANGER_SOLID)
        styleFilledButton(replayDeleteButton, DANGER_SOLID)

        listOf(exportButton, importButton, exportDiffButton, compareButton).forEach {
            styleSoftButton(it, HTTP_ACCENT, BLUE_BUTTON_BG)
        }
        listOf(copyCurlButton, copyJsonButton, copyFetchButton, copyOkHttpButton, copyRetrofitButton).forEach {
            styleSoftButton(it, GRPC_ACCENT, PURPLE_BUTTON_BG)
        }
        listOf(refreshButton, columnsButton, selectAllButton, clearSelectionButton, replayNewButton).forEach {
            styleSoftButton(it, HTTP_ACCENT, BLUE_BUTTON_BG)
        }
        styleSoftButton(pauseButton, ORANGE_ACCENT, ORANGE_BUTTON_BG)
        styleSoftButton(listenButton, DANGER_SOLID, RED_BUTTON_BG)
        listOf(pinButton, metadataButton, openDetailButton, advancedFilterButton).forEach {
            styleSoftButton(it, PREVIEW_ACCENT, PURPLE_BUTTON_BG)
        }
        listOf(expandJsonButton, collapseJsonButton).forEach {
            styleSoftButton(it, HTTP_ACCENT, BLUE_BUTTON_BG)
        }

        installButtonIcons()
        installInformativeTooltips()
    }

    private fun installButtonIcons() {
        configureButton(refreshButton, "Refresh inspectable processes", AllIcons.Actions.Refresh)
        configureButton(connectButton, "Attach to the selected process", AllIcons.Actions.Execute)
        configureButton(listenButton, "Detach from the current process", AllIcons.Actions.Suspend)
        configureButton(pauseButton, "Pause or resume traffic capture", AllIcons.Actions.Pause)
        configureButton(clearButton, "Clear live traffic", AllIcons.Actions.GC)
        configureButton(advancedFilterButton, "Open advanced traffic filters", AllIcons.General.Filter)
        configureButton(columnsButton, "Choose visible table columns", AllIcons.General.Settings)
        configureButton(selectAllButton, "Select all visible traffic", AllIcons.Actions.Expandall)
        configureButton(clearSelectionButton, "Clear traffic selection", AllIcons.Actions.Collapseall)
        configureButton(saveSnapshotButton, "Save selected traffic", AllIcons.Actions.MenuSaveall)
        configureButton(exportButton, "Export traffic", AllIcons.Actions.Copy)
        configureButton(importButton, "Import traffic", AllIcons.Actions.Refresh)
        configureButton(deleteSavedButton, "Delete the selected saved session", AllIcons.Actions.GC)
        configureButton(compareButton, "Compare saved traffic with live traffic", AllIcons.Actions.Refresh)
        configureButton(exportDiffButton, "Export comparison differences", AllIcons.Actions.Copy)

        listOf(
            copyCurlButton to "Copy as a cURL command",
            copyJsonButton to "Copy the preferred JSON body",
            copyFetchButton to "Copy as a JavaScript fetch() snippet",
            copyOkHttpButton to "Copy as an OkHttp Kotlin snippet",
            copyRetrofitButton to "Copy as a Retrofit interface snippet",
        ).forEach { (button, tip) ->
            configureButton(button, tip, AllIcons.Actions.Copy)
        }
        configureButton(replayButton, "Open selected request in Replay", AllIcons.Actions.Execute)
        configureButton(pinButton, "Pin or unpin selected request", AllIcons.General.Add)
        configureButton(metadataButton, "Edit tags and notes", AllIcons.Actions.Edit)
        configureButton(openDetailButton, "Open details in a separate window", AllIcons.Actions.Execute)
        configureButton(expandJsonButton, "Expand the JSON tree", AllIcons.Actions.Expandall)
        configureButton(collapseJsonButton, "Collapse the JSON tree", AllIcons.Actions.Collapseall)

        configureButton(replayNewButton, "Create a new Replay request", AllIcons.General.Add)
        configureButton(replaySaveButton, "Save Replay request", AllIcons.Actions.MenuSaveall)
        configureButton(replaySaveAsButton, "Save as a new Replay request", AllIcons.Actions.MenuSaveall)
        configureButton(replayDeleteButton, "Delete saved Replay request", AllIcons.Actions.GC)
        configureButton(replaySendButton, "Send Replay request", AllIcons.Actions.Execute)

        // Use icon-only buttons only for universally recognizable actions.
        listOf(
            refreshButton,
            pauseButton,
            clearButton,
            advancedFilterButton,
            columnsButton,
            saveSnapshotButton,
            deleteSavedButton,
            expandJsonButton,
            collapseJsonButton,
            replayNewButton,
            replaySaveButton,
            replayDeleteButton,
        ).forEach(::makeIconOnly)
    }

    private fun installInformativeTooltips() {
        processCombo.toolTipText = "Android Studio debug/inspectable process to attach for network capture"
        autoAttach.toolTipText = "Automatically attach when a matching debug process appears"
        bodyLimitCombo.toolTipText = "Maximum captured request/response body size before truncation"
        searchField.toolTipText = "Filter live traffic by method, host, path, or status code"
        httpOnly.toolTipText = "Show only HTTP requests when checked (combine with other protocol filters)"
        wsOnly.toolTipText = "Show only WebSocket handshake traffic when checked"
        sseOnly.toolTipText = "Show only Server-Sent Events (SSE) traffic when checked"
        grpcOnly.toolTipText = "Show only gRPC calls when checked"
        errorsOnly.toolTipText = "Show only failed or error responses (4xx/5xx and capture errors)"
        advancedFilterLabel.toolTipText = "Number of active advanced filters (host, status range, duration, regex)"
        redactExport.toolTipText = "Hide Authorization, cookies, tokens, and other secrets in exported files"
        selectedCountLabel.toolTipText = "How many live traffic rows are currently checked for save/export"
        savedSessionCombo.toolTipText = "Choose a previously saved traffic session to browse or compare"
        detailSearchField.toolTipText = "Search inside the selected exchange headers and body text"
        detailSearchStatus.toolTipText = "Match count for the detail search field"
        replayMethodCombo.toolTipText = "HTTP method used when sending this Replay request"
        replayUrlField.toolTipText = "Full request URL including scheme, host, path, and query"
        replayHeadersArea.toolTipText = "Request headers — one per line in Name: value format"
        replayBodyArea.toolTipText = "Editable request body. Compressed captured bodies are decoded when possible"
        replayResponseHeadersArea.toolTipText = "Response headers returned by the last Send"
        replayResponseBodyArea.toolTipText = "Response body returned by the last Send"
        replayResponseStatus.toolTipText = "Replay editor status and last send result"
        replaySavedList.toolTipText = "Saved Replay requests for this project — click one to load it"
        statusLabel.toolTipText = "Current attach / capture status message"
        healthLabel.toolTipText = "Capture health: idle, healthy, warning, or error"
        table.toolTipText = "Live captured traffic. Check rows to save or export. Select a row for details"
        savedTable.toolTipText = "Traffic from the selected saved session"
        timelinePanel.toolTipText = "Visual timeline of captured requests ordered by start time"
        jsonTree.toolTipText = "Structured JSON tree for the selected request or response body"
        previewPanel.toolTipText = "HTML or image preview when the response content type supports it"

        workspaceTabs.setToolTipTextAt(0, "Inspect live, saved, and timeline network traffic")
        workspaceTabs.setToolTipTextAt(1, "Edit and resend captured HTTP requests like Postman")
        trafficTabs.setToolTipTextAt(0, "Live traffic captured from the attached process")
        trafficTabs.setToolTipTextAt(1, "Saved traffic snapshots for this project")
        trafficTabs.setToolTipTextAt(2, "Timeline view of request timing and overlap")
        detailTabs.setToolTipTextAt(0, "Readable formatted summary of the selected exchange")
        detailTabs.setToolTipTextAt(1, "Raw request headers and body")
        detailTabs.setToolTipTextAt(2, "Raw response headers and body")
        detailTabs.setToolTipTextAt(3, "Timing breakdown for the selected exchange")
        detailTabs.setToolTipTextAt(4, "Expandable JSON tree for request/response bodies")
        detailTabs.setToolTipTextAt(5, "HTML/image preview of the response when available")
    }

    private fun configureButton(button: JButton, tooltip: String, icon: javax.swing.Icon) {
        button.icon = icon
        button.toolTipText = tooltip
        button.iconTextGap = JBUI.scale(4)
        button.horizontalAlignment = SwingConstants.CENTER
    }

    private fun makeIconOnly(button: JButton) {
        val accessibleName = button.toolTipText ?: button.text
        button.text = ""
        button.accessibleContext.accessibleName = accessibleName
        button.accessibleContext.accessibleDescription = button.toolTipText
        // Let the full painted rectangle stay clickable (no shape-clipped hit testing).
        button.margin = Insets(JBUI.scale(2), JBUI.scale(2), JBUI.scale(2), JBUI.scale(2))
        button.preferredSize = null
        button.minimumSize = null
        button.maximumSize = null
    }

    private fun setActionAvailable(button: JButton, available: Boolean) {
        button.isEnabled = available
        if (button.isVisible != available) {
            button.isVisible = available
            button.parent?.revalidate()
            button.parent?.repaint()
        }
    }

    private fun installColoredTabs(
        tabs: JBTabbedPane,
        colors: List<Color>,
        tooltips: List<String> = emptyList(),
    ) {
        if (tabs.tabCount == 0) return
        // Use native tab components so the full tab rectangle is clickable.
        // Custom JLabel tab components only registered clicks on the text glyphs.
        for (index in 0 until tabs.tabCount) {
            tabs.setTabComponentAt(index, null)
            tooltips.getOrNull(index)?.let { tabs.setToolTipTextAt(index, it) }
                ?: tabs.getToolTipTextAt(index)
        }
        fun update() {
            for (index in 0 until tabs.tabCount) {
                val color = colors.getOrElse(index) { HTTP_ACCENT }
                val selected = index == tabs.selectedIndex
                tabs.setForegroundAt(
                    index,
                    if (selected) color else UIManager.getColor("Label.foreground") ?: JBColor.foreground(),
                )
                tabs.setBackgroundAt(
                    index,
                    if (selected) {
                        JBColor(Color(color.red, color.green, color.blue, 28), Color(color.red, color.green, color.blue, 40))
                    } else {
                        UIManager.getColor("TabbedPane.background")
                    },
                )
            }
            tabs.border = JBUI.Borders.empty()
            tabs.repaint()
        }
        // Avoid stacking duplicate listeners if called more than once.
        if (tabs.getClientProperty("oneinsight.coloredTabsInstalled") != true) {
            tabs.putClientProperty("oneinsight.coloredTabsInstalled", true)
            tabs.addChangeListener { update() }
        }
        update()
    }

    private fun styleFilledButton(button: JButton, @Suppress("UNUSED_PARAMETER") backgroundColor: Color) {
        styleSimpleRoundedButton(button)
    }

    private fun styleSoftButton(
        button: JButton,
        @Suppress("UNUSED_PARAMETER") foregroundColor: Color,
        @Suppress("UNUSED_PARAMETER") backgroundColor: Color,
    ) {
        styleSimpleRoundedButton(button)
    }

    private fun styleSimpleRoundedButton(button: JButton) {
        // Do NOT use JButton.buttonType=roundRect: IntelliJ clips mouse hits to the
        // rounded shape, so corners/edges feel unclickable. Keep a full-rectangle hit target.
        button.putClientProperty("JButton.buttonType", null)
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.isBorderPainted = true
        button.isFocusPainted = false
        button.isRolloverEnabled = true
        button.background = UIManager.getColor("Button.background")
        button.foreground = UIManager.getColor("Button.foreground")
        button.margin = Insets(JBUI.scale(2), JBUI.scale(5), JBUI.scale(2), JBUI.scale(5))
        button.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(2, 4),
        )
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // Ensure contains() uses the full component bounds.
        button.putClientProperty("JComponent.noBorderContainsCheck", true)
    }

    private fun wireEvents() {
        refreshButton.addActionListener { refreshProcesses() }
        connectButton.addActionListener {
            val process = processCombo.selectedItem as? InspectableProcess
            if (process != null) {
                service.attachToProcess(process)
            } else {
                service.refreshAndAutoConnect()
            }
        }
        listenButton.addActionListener {
            autoAttach.isSelected = false
            service.setAutoAttach(false)
            service.stopListening()
        }
        autoAttach.addActionListener { service.setAutoAttach(autoAttach.isSelected) }
        bodyLimitCombo.addActionListener {
            val megabytes = (bodyLimitCombo.selectedItem as? String)?.substringBefore(' ')?.toIntOrNull() ?: 5
            service.setMaxBodyBytes(megabytes * 1024 * 1024)
        }
        pauseButton.addActionListener {
            val paused = !service.store.isPaused()
            service.store.setPaused(paused)
            pauseButton.toolTipText = if (paused) {
                "Resume live traffic capture"
            } else {
                "Pause live traffic capture"
            }
            pauseButton.icon = if (paused) AllIcons.Actions.Execute else AllIcons.Actions.Pause
            pauseButton.accessibleContext.accessibleName = pauseButton.toolTipText
            pauseButton.accessibleContext.accessibleDescription = pauseButton.toolTipText
        }
        clearButton.addActionListener { service.store.clear() }
        httpOnly.addActionListener { updateFilter() }
        wsOnly.addActionListener { updateFilter() }
        sseOnly.addActionListener { updateFilter() }
        grpcOnly.addActionListener { updateFilter() }
        errorsOnly.addActionListener { updateFilter() }
        advancedFilterButton.addActionListener { AdvancedFilterDialog().show() }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateFilter()
            override fun removeUpdate(e: DocumentEvent?) = updateFilter()
            override fun changedUpdate(e: DocumentEvent?) = updateFilter()
        })
        detailSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = highlightDetailSearch()
            override fun removeUpdate(e: DocumentEvent?) = highlightDetailSearch()
            override fun changedUpdate(e: DocumentEvent?) = highlightDetailSearch()
        })
        openDetailButton.addActionListener {
            selectedExchange()?.let { TrafficDetailDialog(it).show() }
        }
        copyCurlButton.addActionListener {
            selectedExchange()?.let(::copyCurl)
        }
        copyJsonButton.addActionListener {
            selectedExchange()?.let { copyJson(it, copyJsonButton) }
        }
        copyFetchButton.addActionListener {
            selectedExchange()?.let { copyGenerated(TrafficShare.toFetch(it), copyFetchButton, "Copy Fetch") }
        }
        copyOkHttpButton.addActionListener {
            selectedExchange()?.let { copyGenerated(TrafficShare.toOkHttp(it), copyOkHttpButton, "Copy OkHttp") }
        }
        copyRetrofitButton.addActionListener {
            selectedExchange()?.let {
                copyGenerated(TrafficShare.toRetrofit(it), copyRetrofitButton, "Copy Retrofit")
            }
        }
        replayButton.addActionListener { selectedExchange()?.let(::openReplayTab) }
        replaySendButton.addActionListener { sendReplayRequest() }
        replayNewButton.addActionListener { newReplayRequest() }
        replaySaveButton.addActionListener { saveReplayRequest(saveAs = false) }
        replaySaveAsButton.addActionListener { saveReplayRequest(saveAs = true) }
        replayDeleteButton.addActionListener { deleteReplayRequest() }
        replaySavedList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                replaySavedList.selectedValue?.let(::loadReplayRequest)
                setActionAvailable(replayDeleteButton, replaySavedList.selectedValue != null)
            }
        }
        pinButton.addActionListener {
            selectedExchange()?.takeIf { trafficTabs.selectedIndex == 0 }?.let {
                service.store.updateMetadata(it.correlationId, pinned = !it.pinned)
            }
        }
        metadataButton.addActionListener {
            selectedExchange()?.let { MetadataDialog(it).show() }
        }
        expandJsonButton.addActionListener { expandAll(jsonTree) }
        collapseJsonButton.addActionListener { collapseAll(jsonTree) }
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showSelectedDetails()
            }
        }
        savedTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showSelectedDetails()
                updateHistoryActions()
            }
        }
        trafficTabs.addChangeListener {
            showSelectedDetails()
            updateHistoryActions()
        }
        savedSessionCombo.addActionListener {
            loadSelectedSavedSession()
            updateHistoryActions()
        }
        saveSnapshotButton.addActionListener { saveCurrentSnapshot() }
        columnsButton.addActionListener { ColumnChooserDialog().show() }
        exportButton.addActionListener { exportTraffic() }
        importButton.addActionListener { importTraffic() }
        selectAllButton.addActionListener {
            tableModel.selectAllVisible()
            updateSelectedCount()
        }
        clearSelectionButton.addActionListener {
            tableModel.clearChecks()
            updateSelectedCount()
        }
        deleteSavedButton.addActionListener { deleteSelectedSession() }
        compareButton.addActionListener { compareSelectedWithLive() }
        exportDiffButton.addActionListener { exportSelectedDiff() }
        installShortcuts()
    }

    private fun installShortcuts() {
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun bind(keyCode: Int, name: String, action: () -> Unit) {
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(keyCode, menuMask), name)
            table.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        bind(java.awt.event.KeyEvent.VK_S, "packetins.saveSelected") { saveCurrentSnapshot() }
        bind(java.awt.event.KeyEvent.VK_C, "packetins.copyCurl") {
            selectedExchange()?.let(::copyCurl)
        }
        bind(java.awt.event.KeyEvent.VK_L, "packetins.clearTraffic") { service.store.clear() }
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, menuMask), "packetins.nextMatch")
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_G,
                    menuMask or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                ),
                "packetins.previousMatch",
            )
        table.actionMap.put("packetins.nextMatch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = moveTableSelection(1)
        })
        table.actionMap.put("packetins.previousMatch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = moveTableSelection(-1)
        })
    }

    private fun moveTableSelection(direction: Int) {
        if (table.rowCount == 0) return
        val current = table.selectedRow.takeIf { it >= 0 } ?: if (direction > 0) -1 else 0
        val next = (current + direction + table.rowCount) % table.rowCount
        table.setRowSelectionInterval(next, next)
        table.scrollRectToVisible(table.getCellRect(next, 0, true))
    }

    private fun restorePreferences() {
        for (index in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(index)
            properties.getValue("packetins.column.${column.modelIndex}")?.toIntOrNull()?.let {
                column.preferredWidth = it
            }
        }
        bodyLimitCombo.selectedIndex = properties.getValue("packetins.bodyLimitIndex")
            ?.toIntOrNull()
            ?.coerceIn(0, bodyLimitCombo.itemCount - 1)
            ?: 1
        redactExport.isSelected = properties.getBoolean("packetins.redactExport", true)
        val hidden = properties.getValue("packetins.hiddenColumns")
            ?.split(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()
            .orEmpty()
        applyVisibleColumns(liveColumns.keys - hidden)
        properties.getValue("packetins.sort")
            ?.split(',')
            ?.mapNotNull { encoded ->
                val parts = encoded.split(':')
                val column = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val order = runCatching { SortOrder.valueOf(parts.getOrNull(1).orEmpty()) }.getOrNull()
                    ?: return@mapNotNull null
                RowSorter.SortKey(column, order)
            }
            ?.let { table.rowSorter.sortKeys = it }
    }

    private fun savePreferences() {
        for (index in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(index)
            properties.setValue("packetins.column.${column.modelIndex}", column.width.toString())
        }
        properties.setValue("packetins.bodyLimitIndex", bodyLimitCombo.selectedIndex.toString())
        properties.setValue("packetins.redactExport", redactExport.isSelected, true)
        val visible = (0 until table.columnModel.columnCount)
            .map { table.columnModel.getColumn(it).modelIndex }
            .toSet()
        properties.setValue("packetins.hiddenColumns", (liveColumns.keys - visible).sorted().joinToString(","))
        properties.setValue(
            "packetins.sort",
            table.rowSorter.sortKeys.joinToString(",") { "${it.column}:${it.sortOrder.name}" },
        )
    }

    private fun applyVisibleColumns(visibleModelIndexes: Set<Int>) {
        val required = visibleModelIndexes + 0
        for (index in table.columnModel.columnCount - 1 downTo 0) {
            val column = table.columnModel.getColumn(index)
            if (column.modelIndex !in required) table.removeColumn(column)
        }
        required.sorted().forEach { modelIndex ->
            val column = liveColumns[modelIndex] ?: return@forEach
            if ((0 until table.columnModel.columnCount).none {
                    table.columnModel.getColumn(it).modelIndex == modelIndex
                }) {
                table.addColumn(column)
            }
        }
        required.sorted().forEachIndexed { target, modelIndex ->
            val current = (0 until table.columnModel.columnCount).firstOrNull {
                table.columnModel.getColumn(it).modelIndex == modelIndex
            } ?: return@forEachIndexed
            if (current != target) table.moveColumn(current, target)
        }
    }

    private fun refreshProcesses() {
        processModel.removeAllElements()
        service.listProcesses().forEach { processModel.addElement(it) }
        val selected = service.selectedProcess()
        if (selected != null) {
            for (i in 0 until processModel.size) {
                if (processModel.getElementAt(i).stableId == selected.stableId) {
                    processCombo.selectedIndex = i
                    break
                }
            }
        }
    }

    private fun saveCurrentSnapshot() {
        val selected = tableModel.checkedExchanges()
        if (selected.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "Check one or more rows in the Live tab, then click Save Selected.",
                "OneInsight",
            )
            return
        }
        val defaultName = "Selected ${selected.size} · ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}"
        val name = Messages.showInputDialog(
            project,
            "Name this saved selection (${selected.size} row${if (selected.size == 1) "" else "s"}):",
            "Save OneInsight Traffic",
            Messages.getQuestionIcon(),
            defaultName,
            null,
        ) ?: return
        val session = runCatching { savedTrafficStore.save(name, selected) }
            .getOrElse {
                Messages.showErrorDialog(project, "Could not save traffic: ${it.message}", "OneInsight")
                return
            }
        tableModel.clearChecks()
        updateSelectedCount()
        reloadSavedSessions(session.id)
        trafficTabs.selectedIndex = 1
    }

    private fun exportTraffic() {
        val exchanges = if (trafficTabs.selectedIndex == 1) {
            (savedSessionCombo.selectedItem as? SavedTrafficSession)?.exchanges.orEmpty()
        } else {
            tableModel.checkedExchanges()
        }
        if (exchanges.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "Check rows in Live, or select a session in Saved, before exporting.",
                "Export Traffic",
            )
            return
        }
        val format = Messages.showYesNoCancelDialog(
            project,
            "Choose the export format. Sensitive headers and JSON fields will be redacted when enabled.",
            "Export ${exchanges.size} Traffic Items",
            "HAR",
            "OneInsight JSON",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (format == Messages.CANCEL) return
        val isHar = format == Messages.YES
        val extension = if (isHar) "har" else "json"
        val descriptor = FileSaverDescriptor("Export OneInsight Traffic", "", extension)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val base = project.basePath?.let(Path::of)
        val destination = dialog.save(base, "oneinsight-${System.currentTimeMillis()}.$extension") ?: return
        val content = if (isHar) {
            TrafficShare.toHar(exchanges, redactExport.isSelected)
        } else {
            TrafficShare.toPacketInsJson("OneInsight export", exchanges, redactExport.isSelected)
        }
        runCatching { Files.writeString(destination.file.toPath(), content) }
            .onSuccess {
                Messages.showInfoMessage(project, "Exported ${exchanges.size} items to ${destination.file.name}.", "OneInsight")
            }
            .onFailure {
                Messages.showErrorDialog(project, "Export failed: ${it.message}", "OneInsight")
            }
    }

    private fun importTraffic() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Import OneInsight JSON or HAR")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val imported = runCatching {
            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            val topLevelHar = (parseJson(text) as? JsonObject)?.containsKey("log") == true
            if (file.extension.equals("har", ignoreCase = true) || topLevelHar) {
                file.nameWithoutExtension to TrafficShare.fromHar(text)
            } else {
                val session = TrafficShare.fromPacketInsJson(text)
                session.name to session.exchanges
            }
        }.getOrElse {
            Messages.showErrorDialog(project, "Import failed: ${it.message}", "OneInsight")
            return
        }
        if (imported.second.isEmpty()) {
            Messages.showInfoMessage(project, "The selected file contains no traffic entries.", "OneInsight")
            return
        }
        val session = savedTrafficStore.save("Imported · ${imported.first}", imported.second)
        reloadSavedSessions(session.id)
        trafficTabs.selectedIndex = 1
    }

    private fun reloadReplayRequests(selectId: String? = null) {
        val requests = replayRequestStore.list()
        replaySavedModel.removeAllElements()
        requests.forEach(replaySavedModel::addElement)
        if (selectId != null) {
            val index = requests.indexOfFirst { it.id == selectId }
            if (index >= 0) replaySavedList.selectedIndex = index
        }
    }

    private fun loadReplayRequest(request: SavedReplayRequest) {
        activeReplayId = request.id
        replayMethodCombo.selectedItem = request.method
        replayUrlField.text = request.url
        // Drop transport encodings so Send does not re-compress already-decoded bodies.
        val dropHeaders = setOf("content-length", "content-encoding", "transfer-encoding")
        replayHeadersArea.text = request.headersText.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.isNotEmpty() && dropHeaders.none { drop -> line.startsWith("$drop:", ignoreCase = true) }
            }
            .joinToString("\n")
        val decoded = BodyPreviewDecoder.readableText(
            text = request.bodyText,
            encodingName = null,
            contentType = headerValueFromText(request.headersText, "Content-Type"),
            contentEncodingHeader = headerValueFromText(request.headersText, "Content-Encoding"),
        )
        replayBodyArea.text = when {
            decoded.isNotBlank() -> decoded
            BodyPreviewDecoder.isCompressedGarbage(request.bodyText) -> ""
            else -> request.bodyText
        }
        replayResponseStatus.text = when {
            decoded.isNotBlank() && BodyPreviewDecoder.isCompressedGarbage(request.bodyText) ->
                "Loaded · ${request.name} · body decompressed"
            decoded.isBlank() && BodyPreviewDecoder.isCompressedGarbage(request.bodyText) ->
                "Loaded · ${request.name} · body was binary/gzip and could not be decoded"
            else -> "Loaded · ${request.name}"
        }
        replayResponseStatus.foreground = HEALTHY_FG
        replayResponseHeadersArea.text = ""
        replayResponseBodyArea.text = emptyReplayResponseHtml("Saved request loaded. Edit it or click Send.")
    }

    private fun headerValueFromText(headersText: String, name: String): String? =
        headersText.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun newReplayRequest() {
        activeReplayId = null
        replaySavedList.clearSelection()
        replayMethodCombo.selectedItem = "GET"
        replayUrlField.text = ""
        replayHeadersArea.text = ""
        replayBodyArea.text = ""
        replayResponseHeadersArea.text = ""
        replayResponseBodyArea.text = emptyReplayResponseHtml("Create a request, then Save or Send it.")
        replayResponseStatus.text = "New unsaved request"
        replayResponseStatus.foreground = WARNING_FG
        setActionAvailable(replayDeleteButton, false)
        replayUrlField.requestFocusInWindow()
    }

    private fun saveReplayRequest(saveAs: Boolean) {
        val method = (replayMethodCombo.selectedItem as? String).orEmpty()
        val url = replayUrlField.text.trim()
        if (url.isBlank()) {
            Messages.showErrorDialog(project, "Enter a URL before saving.", "Save Replay Request")
            return
        }
        val existing = (0 until replaySavedModel.size)
            .map(replaySavedModel::getElementAt)
            .firstOrNull { it.id == activeReplayId }
        val name = if (!saveAs && existing != null) {
            existing.name
        } else {
            Messages.showInputDialog(
                project,
                "Name this request:",
                "Save Replay Request",
                Messages.getQuestionIcon(),
                existing?.name ?: "$method ${runCatching { URI(url).path }.getOrNull().orEmpty().ifBlank { url }}",
                null,
            )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        }
        val saved = replayRequestStore.save(
            existingId = if (saveAs) null else activeReplayId,
            name = name,
            method = method,
            url = url,
            headersText = replayHeadersArea.text,
            bodyText = replayBodyArea.text,
        )
        activeReplayId = saved.id
        reloadReplayRequests(saved.id)
        replayResponseStatus.text = "Saved · ${saved.name}"
        replayResponseStatus.foreground = HEALTHY_FG
    }

    private fun deleteReplayRequest() {
        val request = replaySavedList.selectedValue ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Delete saved request \"${request.name}\"?",
            "Delete Replay Request",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        replayRequestStore.delete(request)
        newReplayRequest()
        reloadReplayRequests()
    }

    private fun openReplayTab(exchange: InspectedExchange) {
        activeReplayId = null
        replaySavedList.clearSelection()
        setActionAvailable(replayDeleteButton, false)
        replayMethodCombo.selectedItem = exchange.method.uppercase(Locale.US)
        replayUrlField.text = exchange.url
        val contentEncoding = exchange.requestHeaders
            .firstOrNull { it.name.equals("Content-Encoding", ignoreCase = true) }
            ?.value
        val rawBody = exchange.requestBody?.text
        val decodedBody = BodyPreviewDecoder.readableText(
            text = rawBody,
            encodingName = exchange.requestBody?.encoding?.name,
            contentType = exchange.requestBody?.contentType
                ?: exchange.requestHeaders.firstOrNull { it.name.equals("Content-Type", true) }?.value,
            contentEncodingHeader = contentEncoding,
        )
        // After decoding compressed payloads, do not re-send Content-Encoding / Length.
        val dropHeaders = setOf("content-length", "content-encoding", "transfer-encoding")
        replayHeadersArea.text = exchange.requestHeaders
            .filterNot { it.name.lowercase(Locale.US) in dropHeaders }
            .joinToString("\n") { "${it.name}: ${it.value}" }
        // Never put gzip/mojibake into the editor — only human-readable text.
        replayBodyArea.text = decodedBody
        val wasBinary = !rawBody.isNullOrBlank() && (
            BodyPreviewDecoder.isCompressedGarbage(rawBody) || decodedBody != rawBody
            )
        replayResponseStatus.text = when {
            decodedBody.isNotBlank() && wasBinary ->
                "Ready · body decompressed for editing"
            decodedBody.isBlank() && !rawBody.isNullOrBlank() ->
                "Body was gzip/binary and could not be decoded — clear Live, reinstall plugin, and re-capture"
            else -> "Ready · copied from recorded traffic"
        }
        replayResponseStatus.foreground = HEALTHY_FG
        replayResponseHeadersArea.text = ""
        replayResponseBodyArea.text = emptyReplayResponseHtml("Edit the request, then click Send.")
        workspaceTabs.selectedIndex = workspaceTabs.indexOfTab("Replay")
        replayUrlField.requestFocusInWindow()
    }

    private fun sendReplayRequest() {
        val method = (replayMethodCombo.selectedItem as? String).orEmpty().uppercase(Locale.US)
        val url = replayUrlField.text.trim()
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            Messages.showErrorDialog(project, "Enter a valid HTTP or HTTPS URL.", "OneInsight Replay")
            return
        }
        if (method in setOf("POST", "PUT", "PATCH", "DELETE")) {
            val answer = Messages.showYesNoDialog(
                project,
                "$method may change server data. Send this request from the IDE host?",
                "Confirm Replay",
                "Send",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }

        val bodyText = replayBodyArea.text
        val publisher = if (bodyText.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(bodyText, Charsets.UTF_8)
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .method(method, publisher)
        val restricted = setOf(
            "content-length",
            "host",
            "connection",
            "expect",
            "upgrade",
            "transfer-encoding",
            "content-encoding",
        )
        replayHeadersArea.text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && ':' in it }
            .forEach { line ->
                val name = line.substringBefore(':').trim()
                val value = line.substringAfter(':').trim()
                if (name.lowercase(Locale.US) !in restricted) {
                    runCatching { builder.header(name, value) }
                }
            }

        replaySendButton.isEnabled = false
        replaySendButton.text = "Sending…"
        replayResponseStatus.text = "Sending $method…"
        replayResponseStatus.foreground = WARNING_FG
        val startedNs = System.nanoTime()
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            .whenComplete { response, error ->
                ApplicationManager.getApplication().invokeLater {
                    replaySendButton.text = "Send"
                    replaySendButton.isEnabled = true
                    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                    if (error != null) {
                        replayResponseStatus.text = "Failed · $elapsedMs ms"
                        replayResponseStatus.foreground = ERROR_FG
                        replayResponseHeadersArea.text = ""
                        replayResponseBodyArea.text = emptyReplayResponseHtml(
                            "Request failed: ${escapeHtml(error.cause?.message ?: error.message.orEmpty())}",
                        )
                        return@invokeLater
                    }
                    val bytes = response.body()
                    val contentType = response.headers().firstValue("Content-Type").orElse("")
                    val contentEncoding = response.headers().firstValue("Content-Encoding").orElse("")
                    val decodedText = BodyPreviewDecoder.readableFromBytes(
                        bytes = bytes,
                        contentType = contentType.ifBlank { null },
                        contentEncodingHeader = contentEncoding.ifBlank { null },
                    )
                    val textual = decodedText.isNotBlank()
                    val bodyPreview = when {
                        textual -> decodedText
                        bytes.isEmpty() -> "(empty body)"
                        else ->
                            "Binary response (${bytes.size} bytes, ${contentType.ifBlank { "unknown content type" }}" +
                                contentEncoding.takeIf { it.isNotBlank() }?.let { ", encoding=$it" }.orEmpty() +
                                ").\n\n" + Base64.getEncoder().encodeToString(bytes)
                    }
                    replayResponseStatus.text =
                        "HTTP ${response.statusCode()} · $elapsedMs ms · ${bytes.size} bytes" +
                            if (textual && contentEncoding.isNotBlank()) " · decoded $contentEncoding" else ""
                    replayResponseStatus.foreground =
                        if (response.statusCode() < 400) HEALTHY_FG else ERROR_FG
                    replayResponseHeadersArea.text = response.headers().map()
                        .entries
                        .sortedBy { it.key.lowercase(Locale.US) }
                        .joinToString("\n") { (name, values) -> "$name: ${values.joinToString(", ")}" }
                    replayResponseBodyArea.text = """
                        <html><body style='font-family:monospace;padding:8px'>
                        <pre>${if (textual) syntaxHighlightedBody(bodyPreview) else escapeHtml(bodyPreview)}</pre>
                        </body></html>
                    """.trimIndent()
                    replayResponseBodyArea.caretPosition = 0
                }
            }
    }

    private fun emptyReplayResponseHtml(message: String): String =
        "<html><body style='font-family:sans-serif;text-align:center;padding:36px'>" +
            "<h3>OneInsight Replay</h3><p>${escapeHtml(message)}</p></body></html>"

    private fun reloadSavedSessions(selectId: String? = null) {
        val sessions = savedTrafficStore.list()
        savedSessionModel.removeAllElements()
        sessions.forEach(savedSessionModel::addElement)
        val selectedIndex = sessions.indexOfFirst { it.id == selectId }.takeIf { it >= 0 } ?: 0
        if (sessions.isNotEmpty()) savedSessionCombo.selectedIndex = selectedIndex
        loadSelectedSavedSession()
        updateHistoryActions()
    }

    private fun loadSelectedSavedSession() {
        val session = savedSessionCombo.selectedItem as? SavedTrafficSession
        savedTableModel.setExchanges(session?.exchanges.orEmpty())
        if (savedTable.rowCount > 0) savedTable.setRowSelectionInterval(0, 0)
        showSelectedDetails()
    }

    private fun deleteSelectedSession() {
        val session = savedSessionCombo.selectedItem as? SavedTrafficSession ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Delete saved session \"${session.name}\"?",
            "Delete Saved Traffic",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        savedTrafficStore.delete(session)
        reloadSavedSessions()
    }

    private fun compareSelectedWithLive() {
        val saved = savedTableModel.getExchange(savedTable, savedTable.selectedRow) ?: return
        val live = matchingLive(saved)
        if (live == null) {
            Messages.showInfoMessage(
                project,
                "No matching live request was found for ${saved.method} ${saved.url.ifBlank { saved.path }}.",
                "Compare Traffic",
            )
            return
        }
        CompareTrafficDialog(saved, live).show()
    }

    private fun exportSelectedDiff() {
        val session = savedSessionCombo.selectedItem as? SavedTrafficSession ?: return
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export OneInsight Regression Report", "", "html"),
            project,
        )
        val destination = dialog.save(project.basePath?.let(Path::of), "oneinsight-diff.html") ?: return
        runCatching { Files.writeString(destination.file.toPath(), buildRegressionReport(session)) }
            .onFailure { Messages.showErrorDialog(project, "Export failed: ${it.message}", "OneInsight") }
    }

    private fun matchingLive(saved: InspectedExchange): InspectedExchange? =
        allExchanges.firstOrNull {
            it.method.equals(saved.method, ignoreCase = true) &&
                it.url.ifBlank { it.path } == saved.url.ifBlank { saved.path }
        }

    private fun updateHistoryActions() {
        val savedTab = trafficTabs.selectedIndex == 1
        setActionAvailable(deleteSavedButton, savedTab && savedSessionCombo.selectedItem != null)
        setActionAvailable(compareButton, savedTab && savedTable.selectedRow >= 0)
        setActionAvailable(exportDiffButton, savedTab && savedSessionCombo.selectedItem != null)
    }

    private fun updateFilter() {
        filter = filter.copy(
            query = searchField.text.orEmpty(),
            httpOnly = httpOnly.isSelected,
            websocketOnly = wsOnly.isSelected,
            sseOnly = sseOnly.isSelected,
            grpcOnly = grpcOnly.isSelected,
            errorsOnly = errorsOnly.isSelected,
        )
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = allExchanges.filter { filter.matches(it) }
        timelinePanel.setExchanges(filtered)
        val selectedId = tableModel.getExchange(table, table.selectedRow)?.correlationId
        tableModel.setExchanges(filtered)
        updateSelectedCount()
        if (selectedId != null) {
            val idx = filtered.indexOfFirst { it.correlationId == selectedId }
            if (idx >= 0) {
                table.setRowSelectionInterval(idx, idx)
            }
        }
        showSelectedDetails()
    }

    private fun updateSelectedCount() {
        val count = tableModel.checkedCount()
        selectedCountLabel.text = if (count == 1) "● 1 selected" else "● $count selected"
        selectedCountLabel.foreground = if (count > 0) HEALTHY_FG else JBColor.GRAY
        selectedCountLabel.isVisible = count > 0
        redactExport.isVisible = count > 0
        setActionAvailable(clearSelectionButton, count > 0)
        setActionAvailable(saveSnapshotButton, count > 0)
        setActionAvailable(exportButton, count > 0)
        selectedCountLabel.parent?.revalidate()
        selectedCountLabel.parent?.repaint()
    }

    private fun configureTrafficTable(target: JBTable, selectable: Boolean) {
        target.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        target.autoCreateRowSorter = true
        target.fillsViewportHeight = true
        target.rowHeight = JBUI.scale(26)
        target.showHorizontalLines = false
        target.showVerticalLines = false
        target.intercellSpacing = Dimension(0, JBUI.scale(1))
        target.tableHeader.reorderingAllowed = false
        target.setDefaultRenderer(Any::class.java, ExchangeRowRenderer(selectable))
        target.setDefaultRenderer(Boolean::class.java, target.getDefaultRenderer(Boolean::class.java))
        var index = 0
        if (selectable) {
            target.columnModel.getColumn(index).preferredWidth = JBUI.scale(36)
            target.columnModel.getColumn(index).maxWidth = JBUI.scale(44)
            target.columnModel.getColumn(index).minWidth = JBUI.scale(32)
            index++
        }
        target.columnModel.getColumn(index++).preferredWidth = JBUI.scale(95)
        target.columnModel.getColumn(index++).preferredWidth = JBUI.scale(68)
        target.columnModel.getColumn(index++).preferredWidth = JBUI.scale(58)
        target.columnModel.getColumn(index++).preferredWidth = JBUI.scale(150)
        target.columnModel.getColumn(index++).preferredWidth = JBUI.scale(340)
        target.columnModel.getColumn(index++).preferredWidth = JBUI.scale(75)
        target.columnModel.getColumn(index).preferredWidth = JBUI.scale(90)
    }

    private fun showSelectedDetails() {
        val exchange = selectedExchange()
        detailArea.text = exchange?.let { formatDetails(it) } ?: emptyDetailsHtml()
        requestArea.text = exchange?.let { formatSingleMessage("REQUEST", it.requestHeaders, it.requestBody) }
            ?: emptyDetailsHtml()
        responseArea.text = exchange?.let { formatSingleMessage("RESPONSE", it.responseHeaders, it.responseBody) }
            ?: emptyDetailsHtml()
        timingArea.text = exchange?.let(::formatTiming) ?: emptyDetailsHtml()
        detailArea.caretPosition = 0
        requestArea.caretPosition = 0
        responseArea.caretPosition = 0
        timingArea.caretPosition = 0
        jsonTree.model = DefaultTreeModel(buildJsonRoot(exchange))
        jsonTree.isRootVisible = false
        if (exchange != null) expandFirstLevel(jsonTree)
        updatePreview(exchange)
        setActionAvailable(openDetailButton, exchange != null)
        setActionAvailable(copyCurlButton, exchange != null && exchange.url.isNotBlank())
        setActionAvailable(copyJsonButton, exchange?.let(::preferredJson) != null)
        setActionAvailable(copyFetchButton, exchange != null && exchange.url.isNotBlank())
        setActionAvailable(copyOkHttpButton, exchange != null && exchange.url.isNotBlank())
        setActionAvailable(copyRetrofitButton, exchange != null && exchange.url.startsWith("http"))
        setActionAvailable(replayButton, exchange != null && exchange.url.startsWith("http"))
        val editableLive = exchange != null && trafficTabs.selectedIndex == 0
        setActionAvailable(pinButton, editableLive)
        pinButton.text = if (exchange?.pinned == true) "Unpin" else "Pin"
        setActionAvailable(metadataButton, exchange != null && trafficTabs.selectedIndex != 2)
        val hasJson = exchange?.let(::preferredJson) != null
        setActionAvailable(expandJsonButton, hasJson)
        setActionAvailable(collapseJsonButton, hasJson)
        copyCurlButton.parent?.isVisible = listOf(
            copyCurlButton,
            copyJsonButton,
            copyFetchButton,
            copyOkHttpButton,
            copyRetrofitButton,
        ).any { it.isVisible }
        replayButton.parent?.isVisible =
            listOf(replayButton, pinButton, metadataButton, openDetailButton).any { it.isVisible }
        detailTabs.parent?.revalidate()
        highlightDetailSearch()
    }

    private fun updatePreview(exchange: InspectedExchange?) {
        val kind = previewKind(exchange)
        when (kind) {
            PreviewKind.HTML -> {
                val html = exchange?.responseBody?.text.orEmpty()
                previewHtmlPane.contentType = "text/html"
                previewHtmlPane.text = if (html.contains("<html", ignoreCase = true)) {
                    html
                } else {
                    "<html><body>$html</body></html>"
                }
                previewHtmlPane.caretPosition = 0
                previewImageLabel.icon = null
                previewImageLabel.text = ""
                previewCards.show(previewPanel, "html")
            }
            PreviewKind.IMAGE -> {
                val image = decodePreviewImage(exchange)
                if (image != null) {
                    val maxWidth = JBUI.scale(720)
                    val maxHeight = JBUI.scale(520)
                    val scaled = if (image.width > maxWidth || image.height > maxHeight) {
                        val ratio = min(
                            maxWidth.toDouble() / image.width,
                            maxHeight.toDouble() / image.height,
                        )
                        image.getScaledInstance(
                            (image.width * ratio).toInt().coerceAtLeast(1),
                            (image.height * ratio).toInt().coerceAtLeast(1),
                            Image.SCALE_SMOOTH,
                        )
                    } else {
                        image
                    }
                    previewImageLabel.icon = ImageIcon(scaled)
                    previewImageLabel.text =
                        "<html><div style='padding-top:8px;color:gray'>${image.width}×${image.height} · " +
                            "${escapeHtml(exchange?.responseBody?.contentType ?: "image")} · " +
                            "${exchange?.responseBody?.sizeBytes ?: 0} bytes</div></html>"
                    previewCards.show(previewPanel, "image")
                } else if (isSvgPreview(exchange)) {
                    val svg = exchange?.responseBody?.text.orEmpty()
                    previewHtmlPane.contentType = "text/html"
                    previewHtmlPane.text = "<html><body style='margin:12px'>$svg</body></html>"
                    previewHtmlPane.caretPosition = 0
                    previewCards.show(previewPanel, "html")
                } else {
                    previewEmptyLabel.text =
                        "<html><div style='padding:16px'>Image response detected, but the body could not be decoded.</div></html>"
                    previewCards.show(previewPanel, "empty")
                }
            }
            PreviewKind.NONE -> {
                previewHtmlPane.text = ""
                previewImageLabel.icon = null
                previewImageLabel.text = ""
                previewEmptyLabel.text =
                    "<html><div style='padding:16px'>No HTML or image preview for this response.<br>" +
                        "Preview appears when the URL or Content-Type indicates HTML or an image.</div></html>"
                previewCards.show(previewPanel, "empty")
            }
        }
    }

    private enum class PreviewKind { NONE, HTML, IMAGE }

    private fun previewKind(exchange: InspectedExchange?): PreviewKind {
        if (exchange == null) return PreviewKind.NONE
        val contentType = (
            exchange.responseBody?.contentType
                ?: exchange.responseHeaders.firstOrNull { it.name.equals("Content-Type", true) }?.value
                ?: ""
        ).lowercase(Locale.US)
        val urlHint = "${exchange.url} ${exchange.path}".lowercase(Locale.US)
        val looksImage = contentType.startsWith("image/") ||
            Regex("""\.(png|jpe?g|gif|webp|bmp|ico|svg)(\?|#|$)""")
                .containsMatchIn(urlHint) ||
            urlHint.contains("/image") ||
            urlHint.contains("image/")
        val looksHtml = contentType.contains("text/html") ||
            contentType.contains("application/xhtml") ||
            Regex("""\.(html?|xhtml)(\?|#|$)""").containsMatchIn(urlHint) ||
            urlHint.contains("/html") ||
            urlHint.contains("html=")
        val bodyLooksHtml = exchange.responseBody?.text
            ?.trimStart()
            ?.let { it.startsWith("<!doctype html", true) || it.startsWith("<html", true) } == true
        return when {
            looksImage -> PreviewKind.IMAGE
            looksHtml || bodyLooksHtml -> PreviewKind.HTML
            else -> PreviewKind.NONE
        }
    }

    private fun isSvgPreview(exchange: InspectedExchange?): Boolean {
        val contentType = exchange?.responseBody?.contentType.orEmpty().lowercase(Locale.US)
        val url = "${exchange?.url.orEmpty()} ${exchange?.path.orEmpty()}".lowercase(Locale.US)
        val text = exchange?.responseBody?.text.orEmpty().trimStart()
        return contentType.contains("svg") ||
            url.contains(".svg") ||
            text.startsWith("<svg", ignoreCase = true)
    }

    private fun decodePreviewImage(exchange: InspectedExchange?): BufferedImage? {
        val body = exchange?.responseBody ?: return null
        val text = body.text?.takeIf { it.isNotBlank() } ?: return null
        val bytes = when (body.encoding) {
            dev.packetins.protocol.BodyEncoding.BASE64 ->
                runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            else -> {
                runCatching { Base64.getDecoder().decode(text) }.getOrNull()
                    ?: text.toByteArray(Charsets.ISO_8859_1)
            }
        } ?: return null
        return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    }

    private fun createReadOnlyHtmlPane(): JEditorPane = JEditorPane("text/html", "").apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        border = JBUI.Borders.empty(8)
    }

    private fun formatSingleMessage(
        title: String,
        headers: List<dev.packetins.protocol.HeaderEntry>,
        body: dev.packetins.protocol.BodyPayload?,
    ): String = buildString {
        val foreground = colorHex(UIManager.getColor("TextArea.foreground") ?: JBColor.foreground())
        val panel = colorHex(UIManager.getColor("Panel.background") ?: JBColor.background())
        append("<html><body style='font-family:sans-serif;color:#$foreground;background:#$panel'>")
        append("<h2>${escapeHtml(title)}</h2><h3>Headers</h3><pre>")
        if (headers.isEmpty()) append("(none)")
        headers.forEach { append("${escapeHtml(it.name)}: ${escapeHtml(it.value)}\n") }
        append("</pre><h3>Body</h3>")
        if (body?.text.isNullOrBlank()) {
            append("<i>No body captured.</i>")
        } else {
            val readable = BodyPreviewDecoder.readableText(
                text = body?.text,
                encodingName = body?.encoding?.name,
                contentType = body?.contentType,
                contentEncodingHeader = headers.firstOrNull { it.name.equals("Content-Encoding", true) }?.value,
            )
            append("<div>${escapeHtml(body?.contentType ?: "unknown")} · ${body?.sizeBytes ?: 0} bytes</div>")
            if (readable.isBlank()) {
                append("<i>Body is compressed/binary and could not be decoded for preview.</i>")
            } else {
                append("<pre>${syntaxHighlightedBody(readable)}</pre>")
            }
        }
        append("</body></html>")
    }

    private fun formatTiming(exchange: InspectedExchange): String {
        val duration = exchange.durationMs ?: maxOf(0, exchange.updatedAtMs - exchange.startedAtMs)
        return """
            <html><body style='font-family:sans-serif'>
            <h2>TIMING</h2>
            <table cellpadding='7'>
              <tr><td><b>Started</b></td><td>${escapeHtml(timeFormat.format(Date(exchange.startedAtMs)))}</td></tr>
              <tr><td><b>Last event</b></td><td>${escapeHtml(timeFormat.format(Date(exchange.updatedAtMs)))}</td></tr>
              <tr><td><b>Total duration</b></td><td>$duration ms</td></tr>
              <tr><td><b>Captured size</b></td><td>${(exchange.requestBody?.sizeBytes ?: 0) + (exchange.responseBody?.sizeBytes ?: 0)} bytes</td></tr>
            </table>
            <p>Studio's inspector reports total elapsed time; DNS, TLS, connect, and TTFB phases are not exposed separately.</p>
            </body></html>
        """.trimIndent()
    }

    private fun selectedExchange(): InspectedExchange? =
        when (trafficTabs.selectedIndex) {
            0 -> tableModel.getExchange(table, table.selectedRow)
            1 -> savedTableModel.getExchange(savedTable, savedTable.selectedRow)
            else -> null
        }

    private fun formatDetails(exchange: InspectedExchange): String = buildString {
        val foreground = colorHex(UIManager.getColor("TextArea.foreground") ?: JBColor.foreground())
        val muted = colorHex(UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY)
        val border = colorHex(UIManager.getColor("Separator.foreground") ?: JBColor.GRAY)
        val panelColor = UIManager.getColor("Panel.background") ?: JBColor.background()
        val panel = colorHex(panelColor)
        val dark = panelColor.red + panelColor.green + panelColor.blue < 384
        val card = if (dark) "323438" else "ffffff"
        val requestCard = if (dark) "3b3226" else "fff8e8"
        val responseCard = if (dark) "26372c" else "edf8f1"
        val websocketCard = if (dark) "352a3d" else "f7effb"
        val sseCard = if (dark) "203936" else "e9f8f5"
        val url = exchange.url.ifBlank { exchange.path }.ifBlank { "(URL unavailable)" }
        val methodColor = methodColor(exchange.method)
        val statusColor = statusColor(exchange)

        append("<html><head><style>")
        append("body{font-family:sans-serif;color:#$foreground;background:#$panel;margin:8px;}")
        append("h1{font-size:16px;margin:0;} h2{font-size:14px;margin:0 0 8px 0;}")
        append(".meta{border-collapse:collapse;margin-bottom:10px}.meta td{padding:2px 14px 2px 0;}")
        append(".hero{background:#$card;border:1px solid #$border;margin-bottom:12px;padding:10px;}")
        append(".section{border:1px solid #$border;padding:10px;margin:10px 0 14px 0;}")
        append(".request{background:#$requestCard}.response{background:#$responseCard}")
        append(".websocket{background:#$websocketCard}.sse{background:#$sseCard}")
        append(".label{color:#$muted;font-weight:bold}.box{background:#$card;border:1px solid #$border;padding:7px;margin:4px 0 10px 0;}")
        append("pre{font-family:monospace;white-space:pre-wrap;margin:4px 0 0 0;}")
        append(".empty{color:#$muted;font-style:italic}.error{color:#B3261E;font-weight:bold;}")
        append("</style></head><body>")

        append("<div class='hero'>")
        append("<table width='100%' cellpadding='6'><tr>")
        append("<td bgcolor='#$methodColor'><b><font color='#ffffff'>${escapeHtml(exchange.method)}</font></b></td>")
        append("<td><b>${escapeHtml(url)}</b></td>")
        append("<td bgcolor='#$statusColor' align='center'><b><font color='#ffffff'>${escapeHtml(exchange.displayStatus)}</font></b></td>")
        append("</tr></table>")
        append("<table class='meta'>")
        metaRow("Type", exchange.kind.name)
        metaRow("Duration", exchange.durationMs?.let { "$it ms" } ?: "Pending")
        metaRow("Host", exchange.host.ifBlank { "Unavailable" })
        metaRow("Package", exchange.packageName ?: "Unavailable")
        metaRow("Pinned", if (exchange.pinned) "Yes" else "No")
        if (exchange.tags.isNotEmpty()) metaRow("Tags", exchange.tags.joinToString(", "))
        metaRow("Started", timeFormat.format(Date(exchange.startedAtMs)))
        metaRow("Last updated", timeFormat.format(Date(exchange.updatedAtMs)))
        append("</table></div>")

        if (!exchange.note.isNullOrBlank()) {
            append("<div class='box'><b>Note</b><pre>${escapeHtml(exchange.note)}</pre></div>")
        }
        if (!exchange.errorMessage.isNullOrBlank()) {
            append("<div class='box error'>Error: ${escapeHtml(exchange.errorMessage)}</div>")
        }

        append("<div class='section request'><h2><font color='#D97706'>REQUEST</font></h2>")
        appendHeaders(exchange.requestHeaders.map { it.name to it.value })
        appendBody("Request body", exchange.requestBody)
        append("</div>")

        append("<div class='section response'><h2><font color='#16803A'>RESPONSE</font></h2>")
        appendHeaders(exchange.responseHeaders.map { it.name to it.value })
        appendBody("Response body", exchange.responseBody)
        append("</div>")

        if (exchange.kind == ExchangeKind.WEBSOCKET) {
            append("<div class='section websocket'><h2><font color='#8E44AD'>WEBSOCKET FRAMES</font></h2>")
            if (exchange.wsFrames.isEmpty()) {
                append("<div class='empty'>WebSocket handshake detected. Studio's built-in inspector does not expose frame data.</div>")
            } else {
                exchange.wsFrames.forEach { frame ->
                    append("<div class='box'><b>")
                    append(escapeHtml("${timeFormat.format(Date(frame.timestampMs))} · ${frame.direction} · ${frame.opcode ?: "FRAME"}"))
                    append("</b>")
                    frame.message?.takeIf { it.isNotBlank() }?.let { append("<pre>${escapeHtml(it)}</pre>") }
                    frame.body?.text?.let { append("<pre>${escapeHtml(prettyPrintJson(it))}</pre>") }
                    append("</div>")
                }
            }
            append("</div>")
        }
        if (exchange.kind == ExchangeKind.SSE) {
            appendSseEvents(exchange.responseBody?.text)
        }
        append("</body></html>")
    }

    private fun emptyDetailsHtml(): String {
        val foreground = colorHex(UIManager.getColor("TextArea.foreground") ?: JBColor.foreground())
        val muted = colorHex(UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY)
        val panel = colorHex(UIManager.getColor("Panel.background") ?: JBColor.background())
        return """
            <html>
              <body style="font-family:sans-serif;color:#$foreground;background:#$panel;text-align:center;margin:36px">
                <h2><font color="#47C95E">OneInsight Traffic Details</font></h2>
                <p><font color="#$muted">Select a request from the table to inspect its URL, timing, headers, and body.</font></p>
                <p><b>Blue</b> HTTP &nbsp; · &nbsp; <font color="#8E44AD"><b>Purple</b></font> WebSocket
                  &nbsp; · &nbsp; <font color="#00897B"><b>Teal</b></font> SSE
                  &nbsp; · &nbsp; <font color="#B3261E"><b>Red</b></font> Error</p>
              </body>
            </html>
        """.trimIndent()
    }

    private fun methodColor(method: String): String = when (method.uppercase(Locale.US)) {
        "GET" -> "1976D2"
        "POST" -> "2E7D32"
        "PUT", "PATCH" -> "E07A00"
        "DELETE" -> "C62828"
        "WS" -> "7B1FA2"
        "SSE" -> "00897B"
        "GRPC" -> "5E35B1"
        else -> "546E7A"
    }

    private fun statusColor(exchange: InspectedExchange): String = when {
        !exchange.errorMessage.isNullOrBlank() -> "B3261E"
        exchange.kind == ExchangeKind.WEBSOCKET -> "7B1FA2"
        exchange.kind == ExchangeKind.SSE -> "00897B"
        exchange.kind == ExchangeKind.GRPC -> "5E35B1"
        exchange.statusCode == null -> "607D8B"
        exchange.statusCode in 200..299 -> "218739"
        exchange.statusCode in 300..399 -> "1976D2"
        exchange.statusCode in 400..499 -> "D97706"
        else -> "B3261E"
    }

    private fun StringBuilder.appendSseEvents(body: String?) {
        append("<div class='section sse'><h2><font color='#00897B'>SERVER-SENT EVENTS</font></h2>")
        val events = parseSseEvents(body)
        if (events.isEmpty()) {
            append("<div class='empty'>SSE stream detected. Waiting for event data from the Studio inspector.</div>")
        } else {
            events.forEachIndexed { index, event ->
                append("<div class='box'><b><font color='#00897B'>")
                append(escapeHtml(event.type ?: "message"))
                append("</font></b>")
                event.id?.let { append(" &nbsp; <span class='empty'>id: ${escapeHtml(it)}</span>") }
                event.retry?.let { append(" &nbsp; <span class='empty'>retry: ${escapeHtml(it)} ms</span>") }
                if (event.data.isNotBlank()) {
                    append("<pre>${escapeHtml(prettyPrintJson(event.data))}</pre>")
                } else {
                    append("<div class='empty'>Event ${index + 1} has no data field.</div>")
                }
                append("</div>")
            }
        }
        append("</div>")
    }

    private data class SseDisplayEvent(
        val type: String?,
        val id: String?,
        val retry: String?,
        val data: String,
    )

    private fun parseSseEvents(body: String?): List<SseDisplayEvent> {
        if (body.isNullOrBlank()) return emptyList()
        val events = mutableListOf<SseDisplayEvent>()
        var type: String? = null
        var id: String? = null
        var retry: String? = null
        val data = mutableListOf<String>()

        fun flush() {
            if (type != null || id != null || retry != null || data.isNotEmpty()) {
                events += SseDisplayEvent(type, id, retry, data.joinToString("\n"))
            }
            type = null
            id = null
            retry = null
            data.clear()
        }

        body.lineSequence().forEach { line ->
            if (line.isEmpty()) {
                flush()
            } else if (!line.startsWith(":")) {
                val field = line.substringBefore(':')
                val value = line.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "event" -> type = value
                    "id" -> id = value
                    "retry" -> retry = value
                    "data" -> data += value
                }
            }
        }
        flush()
        return events
    }

    private fun StringBuilder.metaRow(label: String, value: String) {
        append("<tr><td class='label'>${escapeHtml(label)}</td><td>${escapeHtml(value)}</td></tr>")
    }

    private fun StringBuilder.appendHeaders(headers: List<Pair<String, String>>) {
        append("<div class='label'>Headers</div><div class='box'>")
        if (headers.isEmpty()) {
            append("<span class='empty'>No headers captured.</span>")
        } else {
            append("<pre>")
            headers.forEach { (name, value) -> append("${escapeHtml(name)}: ${escapeHtml(value)}\n") }
            append("</pre>")
        }
        append("</div>")
    }

    private fun StringBuilder.appendBody(title: String, body: dev.packetins.protocol.BodyPayload?) {
        append("<div class='label'>${escapeHtml(title)}</div><div class='box'>")
        if (body == null) {
            append("<span class='empty'>No body captured.</span>")
        } else {
            val metadata = buildString {
                append(body.contentType ?: "Content type unknown")
                append(" · ${body.sizeBytes} bytes · ${body.encoding}")
                if (body.truncated) append(" · truncated")
            }
            append("<span class='empty'>${escapeHtml(metadata)}</span>")
            val text = BodyPreviewDecoder.readableText(
                text = body.text,
                encodingName = body.encoding.name,
                contentType = body.contentType,
                contentEncodingHeader = null,
            ).ifBlank { body.text }
            if (text.isNullOrBlank()) {
                append("<div class='empty'>No preview available.</div>")
            } else {
                append("<pre>${syntaxHighlightedBody(text)}</pre>")
            }
        }
        append("</div>")
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun colorHex(color: Color): String = "%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun syntaxHighlightedBody(value: String): String {
        val json = parseJson(value) ?: return escapeHtml(value)
        fun render(element: JsonElement, indent: Int): String = when (element) {
            is JsonObject -> element.entries.joinToString(
                separator = ",\n",
                prefix = "{\n",
                postfix = "\n${"  ".repeat(indent)}}",
            ) { (key, child) ->
                "${"  ".repeat(indent + 1)}<font color='#7B61A8'>&quot;${escapeHtml(key)}&quot;</font>: " +
                    render(child, indent + 1)
            }
            is JsonArray -> element.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n${"  ".repeat(indent)}]",
            ) { child -> "${"  ".repeat(indent + 1)}${render(child, indent + 1)}" }
            is JsonNull -> "<font color='#8A8A8A'>null</font>"
            is JsonPrimitive -> when {
                element.isString -> "<font color='#218739'>&quot;${escapeHtml(element.content)}&quot;</font>"
                element.content == "true" || element.content == "false" ->
                    "<font color='#1565C0'>${element.content}</font>"
                else -> "<font color='#C45B00'>${escapeHtml(element.content)}</font>"
            }
        }
        return render(json, 0)
    }

    private fun prettyPrintJson(value: String): String {
        val trimmed = value.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return value
        val result = StringBuilder()
        var indent = 0
        var inString = false
        var escaped = false
        trimmed.forEach { char ->
            when {
                escaped -> {
                    result.append(char)
                    escaped = false
                }
                char == '\\' && inString -> {
                    result.append(char)
                    escaped = true
                }
                char == '"' -> {
                    result.append(char)
                    inString = !inString
                }
                inString -> result.append(char)
                char == '{' || char == '[' -> {
                    result.append(char).append('\n')
                    indent++
                    result.append("  ".repeat(indent))
                }
                char == '}' || char == ']' -> {
                    result.append('\n')
                    indent = (indent - 1).coerceAtLeast(0)
                    result.append("  ".repeat(indent)).append(char)
                }
                char == ',' -> result.append(char).append('\n').append("  ".repeat(indent))
                char == ':' -> result.append(": ")
                char.isWhitespace() -> Unit
                else -> result.append(char)
            }
        }
        return result.toString()
    }

    private fun buildJsonTreePanel(
        tree: JTree,
        expandButton: JButton,
        collapseButton: JButton,
    ): JPanel = JPanel(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(expandButton)
            add(collapseButton)
            add(JBLabel("Click the arrows to minimize individual objects and arrays."))
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    private fun buildDialogPreviewPanel(exchange: InspectedExchange): JComponent {
        return when (previewKind(exchange)) {
            PreviewKind.HTML -> {
                val html = exchange.responseBody?.text.orEmpty()
                JBScrollPane(
                    JEditorPane(
                        "text/html",
                        if (html.contains("<html", ignoreCase = true)) html else "<html><body>$html</body></html>",
                    ).apply {
                        isEditable = false
                        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                        caretPosition = 0
                    },
                )
            }
            PreviewKind.IMAGE -> {
                val image = decodePreviewImage(exchange)
                if (image != null) {
                    JBScrollPane(JLabel(ImageIcon(image), SwingConstants.CENTER))
                } else if (isSvgPreview(exchange)) {
                    JBScrollPane(
                        JEditorPane("text/html", "<html><body>${exchange.responseBody?.text.orEmpty()}</body></html>")
                            .apply { isEditable = false },
                    )
                } else {
                    JBLabel("Image body could not be decoded.", SwingConstants.CENTER)
                }
            }
            PreviewKind.NONE -> JBLabel(
                "No HTML or image preview for this response.",
                SwingConstants.CENTER,
            )
        }
    }

    private fun buildJsonRoot(exchange: InspectedExchange?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("JSON")
        if (exchange == null) {
            root.add(DefaultMutableTreeNode("Select traffic to inspect JSON"))
            return root
        }
        var foundJson = false
        listOf(
            "Request" to exchange.requestBody?.text,
            "Response" to exchange.responseBody?.text,
        ).forEach { (label, text) ->
            parseJson(text)?.let { json ->
                root.add(jsonNode(label, json))
                foundJson = true
            }
        }
        if (!foundJson) {
            root.add(DefaultMutableTreeNode("No JSON object or array is available for this traffic item"))
        }
        return root
    }

    private fun parseJson(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return runCatching { PacketInsProtocol.json.parseToJsonElement(trimmed) }.getOrNull()
    }

    private fun jsonNode(label: String, value: JsonElement): DefaultMutableTreeNode = when (value) {
        is JsonObject -> DefaultMutableTreeNode("$label  {${value.size}}").apply {
            value.forEach { (key, child) -> add(jsonNode(key, child)) }
        }
        is JsonArray -> DefaultMutableTreeNode("$label  [${value.size}]").apply {
            value.forEachIndexed { index, child -> add(jsonNode("[$index]", child)) }
        }
        is JsonPrimitive -> {
            val rendered = if (value.isString) "\"${value.content}\"" else value.content
            DefaultMutableTreeNode("$label: ${rendered.take(300)}${if (rendered.length > 300) "…" else ""}")
        }
        JsonNull -> DefaultMutableTreeNode("$label: null")
    }

    private fun highlightDetailSearch() {
        highlightSearch(detailArea, detailSearchField.text.orEmpty(), detailSearchStatus)
    }

    private fun copyCurl(exchange: InspectedExchange, sourceButton: JButton = copyCurlButton) {
        val command = buildCurl(exchange)
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        sourceButton.text = "Copied!"
        Timer(1_200) { sourceButton.text = "Copy cURL" }.apply {
            isRepeats = false
            start()
        }
    }

    private fun copyJson(exchange: InspectedExchange, sourceButton: JButton = copyJsonButton) {
        val json = preferredJson(exchange) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(prettyPrintJson(json)))
        sourceButton.text = "Copied!"
        Timer(1_200) { sourceButton.text = "Copy JSON" }.apply {
            isRepeats = false
            start()
        }
    }

    private fun copyGenerated(content: String, sourceButton: JButton, originalText: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(content))
        sourceButton.text = "Copied!"
        Timer(1_200) { sourceButton.text = originalText }.apply {
            isRepeats = false
            start()
        }
    }

    private fun preferredJson(exchange: InspectedExchange): String? =
        sequenceOf(exchange.responseBody?.text, exchange.requestBody?.text)
            .filterNotNull()
            .firstOrNull { parseJson(it) != null }

    private fun buildCurl(exchange: InspectedExchange): String {
        val arguments = mutableListOf(
            "curl",
            "--request ${shellQuote(exchange.method.ifBlank { "GET" })}",
            "--url ${shellQuote(exchange.url.ifBlank { exchange.path })}",
        )
        exchange.requestHeaders
            .filterNot { it.name.equals("Content-Length", ignoreCase = true) }
            .forEach { header ->
                arguments += "--header ${shellQuote("${header.name}: ${header.value}")}"
            }

        val body = exchange.requestBody
        val bodyText = body?.text
        if (!bodyText.isNullOrEmpty()) {
            arguments += when (body.encoding) {
                dev.packetins.protocol.BodyEncoding.UTF8 -> "--data-raw ${shellQuote(bodyText)}"
                dev.packetins.protocol.BodyEncoding.BASE64 -> "--data-binary @-"
                dev.packetins.protocol.BodyEncoding.NONE -> "--data-raw ${shellQuote(bodyText)}"
            }
        }

        val curl = arguments.joinToString(" \\\n  ")
        return if (!bodyText.isNullOrEmpty() && body.encoding == dev.packetins.protocol.BodyEncoding.BASE64) {
            "printf '%s' ${shellQuote(bodyText)} | openssl base64 -d -A | \\\n  $curl"
        } else {
            curl
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun highlightSearch(editor: JEditorPane, query: String, status: JBLabel) {
        editor.highlighter.removeAllHighlights()
        if (query.isBlank()) {
            status.text = ""
            return
        }
        val text = runCatching { editor.document.getText(0, editor.document.length) }.getOrDefault("")
        val painter = DefaultHighlighter.DefaultHighlightPainter(
            JBColor(Color(0xFFE082), Color(0x715C00))
        )
        var index = text.indexOf(query, ignoreCase = true)
        var matches = 0
        while (index >= 0) {
            runCatching { editor.highlighter.addHighlight(index, index + query.length, painter) }
            matches++
            index = text.indexOf(query, startIndex = index + query.length, ignoreCase = true)
        }
        status.text = if (matches == 1) "1 match" else "$matches matches"
    }

    private fun expandFirstLevel(tree: JTree) {
        tree.expandRow(0)
        var row = 1
        while (row < tree.rowCount && row <= 2) {
            tree.expandRow(row)
            row++
        }
    }

    private fun expandAll(tree: JTree) {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun collapseAll(tree: JTree) {
        for (row in tree.rowCount - 1 downTo 1) {
            tree.collapseRow(row)
        }
    }

    private inner class MetadataDialog(
        private val exchange: InspectedExchange,
    ) : DialogWrapper(project) {
        private val tagsField = JBTextField(exchange.tags.joinToString(", "))
        private val noteArea = JTextArea(exchange.note.orEmpty()).apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 7
        }

        init {
            title = "Tags and Note"
            init()
        }

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(260))
            border = JBUI.Borders.empty(10)
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("Tags (comma-separated):"), BorderLayout.NORTH)
                add(tagsField, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("Debugging note:"), BorderLayout.NORTH)
                add(JBScrollPane(noteArea), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        override fun doOKAction() {
            val tags = tagsField.text.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            val note = noteArea.text.trim().takeIf(String::isNotEmpty)
            if (trafficTabs.selectedIndex == 1) {
                val session = savedSessionCombo.selectedItem as? SavedTrafficSession
                if (session != null) {
                    savedTrafficStore.replace(session.copy(
                        exchanges = session.exchanges.map {
                            if (it.correlationId == exchange.correlationId) it.copy(tags = tags, note = note) else it
                        }
                    ))
                    reloadSavedSessions(session.id)
                }
            } else {
                service.store.updateMetadata(
                    exchange.correlationId,
                    tags = tags,
                    note = note,
                    updateNote = true,
                )
            }
            super.doOKAction()
        }
    }

    private inner class ColumnChooserDialog : DialogWrapper(project) {
        private val visible = (0 until table.columnModel.columnCount)
            .map { table.columnModel.getColumn(it).modelIndex }
            .toSet()
        private val checks = liveColumns.keys.sorted().associateWith { modelIndex ->
            JCheckBox(tableModel.getColumnName(modelIndex), modelIndex in visible).apply {
                if (modelIndex == 0) {
                    isSelected = true
                    isEnabled = false
                    toolTipText = "Selection is required for Save Selected."
                }
            }
        }

        init {
            title = "Choose Live Traffic Columns"
            init()
        }

        override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 2, JBUI.scale(12), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            checks.values.forEach(::add)
        }

        override fun doOKAction() {
            applyVisibleColumns(checks.filterValues { it.isSelected }.keys)
            super.doOKAction()
        }
    }

    private inner class AdvancedFilterDialog : DialogWrapper(project) {
        private val hostField = JBTextField(filter.host).apply {
            toolTipText = "Keep rows whose host contains this text"
        }
        private val statusMinField = JBTextField(filter.statusMin?.toString().orEmpty()).apply {
            toolTipText = "Minimum HTTP status code, inclusive"
        }
        private val statusMaxField = JBTextField(filter.statusMax?.toString().orEmpty()).apply {
            toolTipText = "Maximum HTTP status code, inclusive"
        }
        private val durationMinField = JBTextField(filter.durationMinMs?.toString().orEmpty()).apply {
            toolTipText = "Minimum request duration in milliseconds"
        }
        private val durationMaxField = JBTextField(filter.durationMaxMs?.toString().orEmpty()).apply {
            toolTipText = "Maximum request duration in milliseconds"
        }
        private val regexCheck = JCheckBox("Treat main Search as regular expression", filter.queryIsRegex).apply {
            toolTipText = "Interpret the Live Search box as a Java regular expression"
        }

        init {
            title = "Advanced Traffic Filters"
            init()
        }

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(440), JBUI.scale(230))
            add(JPanel(GridLayout(0, 2, JBUI.scale(8), JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(10)
                add(JBLabel("Host contains:"))
                add(hostField)
                add(JBLabel("Minimum status:"))
                add(statusMinField)
                add(JBLabel("Maximum status:"))
                add(statusMaxField)
                add(JBLabel("Minimum duration (ms):"))
                add(durationMinField)
                add(JBLabel("Maximum duration (ms):"))
                add(durationMaxField)
                add(regexCheck)
                add(JButton("Reset").apply {
                    styleSoftButton(this, DANGER_SOLID, RED_BUTTON_BG)
                    configureButton(this, "Reset all advanced filters", AllIcons.Actions.GC)
                    addActionListener {
                        hostField.text = ""
                        statusMinField.text = ""
                        statusMaxField.text = ""
                        durationMinField.text = ""
                        durationMaxField.text = ""
                        regexCheck.isSelected = false
                    }
                })
            }, BorderLayout.CENTER)
        }

        override fun doOKAction() {
            filter = filter.copy(
                host = hostField.text.trim(),
                statusMin = statusMinField.text.trim().toIntOrNull(),
                statusMax = statusMaxField.text.trim().toIntOrNull(),
                durationMinMs = durationMinField.text.trim().toLongOrNull(),
                durationMaxMs = durationMaxField.text.trim().toLongOrNull(),
                queryIsRegex = regexCheck.isSelected,
            )
            val active = listOf(
                filter.host.isNotBlank(),
                filter.statusMin != null,
                filter.statusMax != null,
                filter.durationMinMs != null,
                filter.durationMaxMs != null,
                filter.queryIsRegex,
            ).count { it }
            advancedFilterLabel.text = if (active == 0) "" else "$active active"
            updateFilter()
            super.doOKAction()
        }
    }

    private inner class TrafficDetailDialog(
        private val exchange: InspectedExchange,
    ) : DialogWrapper(project, false) {
        init {
            title = "${exchange.method} ${exchange.path.ifBlank { exchange.url }}"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val editor = JEditorPane("text/html", formatDetails(exchange)).apply {
                isEditable = false
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                border = JBUI.Borders.empty(8)
                caretPosition = 0
            }
            val tree = JTree(DefaultTreeModel(buildJsonRoot(exchange))).apply {
                isRootVisible = false
                showsRootHandles = true
                rowHeight = JBUI.scale(22)
            }
            expandFirstLevel(tree)
            val expand = JButton("Expand").apply {
                styleSoftButton(this, HTTP_ACCENT, BLUE_BUTTON_BG)
                configureButton(this, "Expand the JSON tree", AllIcons.Actions.Expandall)
                makeIconOnly(this)
                addActionListener { expandAll(tree) }
            }
            val collapse = JButton("Collapse").apply {
                styleSoftButton(this, HTTP_ACCENT, BLUE_BUTTON_BG)
                configureButton(this, "Collapse the JSON tree", AllIcons.Actions.Collapseall)
                makeIconOnly(this)
                addActionListener { collapseAll(tree) }
            }
            val tabs = JBTabbedPane().apply {
                addTab("Formatted", JBScrollPane(editor))
                addTab("JSON Tree", buildJsonTreePanel(tree, expand, collapse))
                addTab("Preview", buildDialogPreviewPanel(exchange))
            }
            val search = JBTextField().apply {
                emptyText.text = "Find in headers or body"
                preferredSize = Dimension(JBUI.scale(260), preferredSize.height)
            }
            val searchStatus = JBLabel("")
            val copyCurlActionButton = JButton("cURL").apply {
                styleSoftButton(this, GRPC_ACCENT, PURPLE_BUTTON_BG)
                configureButton(this, "Copy cURL request", AllIcons.Actions.Copy)
                addActionListener { copyCurl(exchange, this) }
            }
            val copyJsonActionButton = JButton("JSON").apply {
                styleSoftButton(this, GRPC_ACCENT, PURPLE_BUTTON_BG)
                configureButton(this, "Copy JSON body", AllIcons.Actions.Copy)
                isEnabled = preferredJson(exchange) != null
                addActionListener { copyJson(exchange, this) }
            }
            search.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = highlightSearch(editor, search.text, searchStatus)
                override fun removeUpdate(e: DocumentEvent?) = highlightSearch(editor, search.text, searchStatus)
                override fun changedUpdate(e: DocumentEvent?) = highlightSearch(editor, search.text, searchStatus)
            })
            return JPanel(BorderLayout()).apply {
                preferredSize = Dimension(JBUI.scale(1000), JBUI.scale(700))
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(5))).apply {
                    add(JBLabel("Find:"))
                    add(search)
                    add(searchStatus)
                    add(copyCurlActionButton)
                    add(copyJsonActionButton)
                }, BorderLayout.NORTH)
                add(tabs, BorderLayout.CENTER)
            }
        }
    }

    private inner class CompareTrafficDialog(
        private val saved: InspectedExchange,
        private val live: InspectedExchange,
    ) : DialogWrapper(project, false) {
        init {
            title = "Saved vs Live · ${saved.method} ${saved.path.ifBlank { saved.url }}"
            init()
        }

        override fun createCenterPanel(): JComponent {
            fun side(title: String, exchange: InspectedExchange): JPanel {
                val editor = JEditorPane("text/html", formatDetails(exchange)).apply {
                    isEditable = false
                    putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                    border = JBUI.Borders.empty(8)
                    caretPosition = 0
                }
                return JPanel(BorderLayout()).apply {
                    add(JBLabel("<html><b>$title</b></html>").apply {
                        border = JBUI.Borders.empty(6)
                    }, BorderLayout.NORTH)
                    add(JBScrollPane(editor), BorderLayout.CENTER)
                }
            }

            val sideBySide = JBSplitter(false, 0.5f).apply {
                preferredSize = Dimension(JBUI.scale(1200), JBUI.scale(750))
                firstComponent = side("SAVED HISTORY", saved)
                secondComponent = side("LIVE NOW", live)
            }
            val changes = JEditorPane("text/html", buildDiffHtml(saved, live)).apply {
                isEditable = false
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                border = JBUI.Borders.empty(10)
            }
            return JBTabbedPane().apply {
                preferredSize = Dimension(JBUI.scale(1200), JBUI.scale(750))
                addTab("Changes", JBScrollPane(changes))
                addTab("Side by Side", sideBySide)
            }
        }
    }

    private data class DiffLine(
        val field: String,
        val before: String?,
        val after: String?,
    )

    private fun buildRegressionReport(session: SavedTrafficSession): String = buildString {
        append("<html><body style='font-family:sans-serif'><h1>OneInsight regression report</h1>")
        append("<p>Baseline: ${escapeHtml(session.name)} · ${session.exchanges.size} requests</p>")
        append("<table width='100%' border='1' cellspacing='0' cellpadding='6'>")
        append("<tr bgcolor='#455A64'><th><font color='white'>Endpoint</font></th>")
        append("<th><font color='white'>Status</font></th><th><font color='white'>Duration</font></th>")
        append("<th><font color='white'>Response size</font></th></tr>")
        session.exchanges.forEach { saved ->
            val live = matchingLive(saved)
            val status = if (live == null) {
                "Missing in live capture"
            } else {
                "${saved.statusCode ?: "-"} → ${live.statusCode ?: "-"}"
            }
            val duration = if (live == null) "-" else "${saved.durationMs ?: 0} → ${live.durationMs ?: 0} ms"
            val size = if (live == null) {
                "-"
            } else {
                "${saved.responseBody?.sizeBytes ?: 0} → ${live.responseBody?.sizeBytes ?: 0} bytes"
            }
            val changed = live == null ||
                saved.statusCode != live.statusCode ||
                saved.responseBody?.sizeBytes != live.responseBody?.sizeBytes
            append("<tr${if (changed) " bgcolor='#FFF3E0'" else ""}>")
            append("<td>${escapeHtml("${saved.method} ${saved.url.ifBlank { saved.path }}")}</td>")
            append("<td>${escapeHtml(status)}</td><td>${escapeHtml(duration)}</td><td>${escapeHtml(size)}</td></tr>")
        }
        append("</table><p>Generated by OneInsight.</p></body></html>")
    }

    private fun buildDiffHtml(saved: InspectedExchange, live: InspectedExchange): String {
        val differences = mutableListOf<DiffLine>()
        if (saved.statusCode != live.statusCode) {
            differences += DiffLine("Status", saved.statusCode?.toString(), live.statusCode?.toString())
        }
        if (saved.durationMs != live.durationMs) {
            differences += DiffLine("Duration", saved.durationMs?.let { "$it ms" }, live.durationMs?.let { "$it ms" })
        }
        diffHeaders("Request header", saved.requestHeaders, live.requestHeaders, differences)
        diffHeaders("Response header", saved.responseHeaders, live.responseHeaders, differences)
        val savedJson = parseJson(saved.responseBody?.text)
        val liveJson = parseJson(live.responseBody?.text)
        if (savedJson != null || liveJson != null) diffJson("$", savedJson, liveJson, differences)

        return buildString {
            append("<html><body style='font-family:sans-serif'>")
            append("<h2>Regression comparison</h2>")
            if (differences.isEmpty()) {
                append("<p><font color='#218739'><b>No status, header, duration, or JSON differences found.</b></font></p>")
            } else {
                append("<p><b>${differences.size} change${if (differences.size == 1) "" else "s"} found</b></p>")
                append("<table width='100%' border='1' cellspacing='0' cellpadding='6'>")
                append("<tr bgcolor='#455A64'><th><font color='white'>Field</font></th>")
                append("<th><font color='white'>Saved</font></th><th><font color='white'>Live</font></th></tr>")
                differences.take(500).forEach { diff ->
                    append("<tr><td><b>${escapeHtml(diff.field)}</b></td>")
                    append("<td bgcolor='#FDECEC'><pre>${escapeHtml(diff.before ?: "(missing)")}</pre></td>")
                    append("<td bgcolor='#EDF8F1'><pre>${escapeHtml(diff.after ?: "(missing)")}</pre></td></tr>")
                }
                append("</table>")
            }
            append("</body></html>")
        }
    }

    private fun diffHeaders(
        prefix: String,
        before: List<dev.packetins.protocol.HeaderEntry>,
        after: List<dev.packetins.protocol.HeaderEntry>,
        output: MutableList<DiffLine>,
    ) {
        val left = before.groupBy { it.name.lowercase() }.mapValues { it.value.joinToString { h -> h.value } }
        val right = after.groupBy { it.name.lowercase() }.mapValues { it.value.joinToString { h -> h.value } }
        (left.keys + right.keys).sorted().forEach { key ->
            if (left[key] != right[key]) output += DiffLine("$prefix · $key", left[key], right[key])
        }
    }

    private fun diffJson(
        path: String,
        before: JsonElement?,
        after: JsonElement?,
        output: MutableList<DiffLine>,
    ) {
        if (before == after) return
        when {
            before is JsonObject && after is JsonObject -> {
                (before.keys + after.keys).sorted().forEach { key ->
                    diffJson("$path.$key", before[key], after[key], output)
                }
            }
            before is JsonArray && after is JsonArray -> {
                for (index in 0 until maxOf(before.size, after.size)) {
                    diffJson("$path[$index]", before.getOrNull(index), after.getOrNull(index), output)
                }
            }
            else -> output += DiffLine(path, before?.toString(), after?.toString())
        }
    }

    override fun dispose() {
        savePreferences()
        service.store.removeListener(storeListener)
        service.removeStatusListener(statusListener)
        service.removeHealthListener(healthListener)
    }

    private inner class TrafficTimelinePanel : JPanel() {
        private var exchanges: List<InspectedExchange> = emptyList()

        init {
            background = JBColor.namedColor("Table.background", JBColor.background())
        }

        fun setExchanges(value: List<InspectedExchange>) {
            exchanges = value.sortedByDescending { it.startedAtMs }.take(250)
            preferredSize = Dimension(JBUI.scale(950), JBUI.scale(maxOf(220, exchanges.size * 25 + 44)))
            revalidate()
            repaint()
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (exchanges.isEmpty()) {
                    g.color = JBColor.GRAY
                    g.drawString("No traffic matches the current filters.", JBUI.scale(24), JBUI.scale(40))
                    return
                }
                val labelWidth = JBUI.scale(270)
                val chartStart = labelWidth + JBUI.scale(16)
                val chartWidth = maxOf(JBUI.scale(300), width - chartStart - JBUI.scale(24))
                val first = exchanges.minOf { it.startedAtMs }
                val last = exchanges.maxOf { it.startedAtMs + (it.durationMs ?: 1) }
                val span = maxOf(1L, last - first)
                g.color = JBColor.GRAY
                g.drawString("Request", JBUI.scale(8), JBUI.scale(20))
                g.drawString("Timeline / duration", chartStart, JBUI.scale(20))
                exchanges.forEachIndexed { index, exchange ->
                    val y = JBUI.scale(36 + index * 25)
                    if (index % 2 == 0) {
                        g.color = TIMELINE_ROW_BG
                        g.fillRect(0, y - JBUI.scale(3), width, JBUI.scale(23))
                    }
                    val label = "${exchange.method} ${exchange.path.ifBlank { exchange.url }}"
                    g.color = JBColor.namedColor("Table.foreground", JBColor.foreground())
                    g.drawString(label.take(42), JBUI.scale(8), y + JBUI.scale(13))
                    val x = chartStart + (((exchange.startedAtMs - first).toDouble() / span) * chartWidth).toInt()
                    val durationWidth = maxOf(
                        JBUI.scale(4),
                        ((((exchange.durationMs ?: 1).toDouble() / span) * chartWidth)).toInt(),
                    )
                    g.color = when {
                        exchange.errorMessage != null || (exchange.statusCode ?: 0) >= 400 -> ERROR_BG
                        exchange.kind == ExchangeKind.SSE -> SSE_BG
                        exchange.kind == ExchangeKind.WEBSOCKET -> WS_BG
                        else -> Color.decode("#${statusColor(exchange)}")
                    }
                    g.fillRoundRect(x, y, durationWidth, JBUI.scale(16), JBUI.scale(6), JBUI.scale(6))
                    g.color = JBColor.GRAY
                    g.drawString("${exchange.durationMs ?: 0} ms", x + durationWidth + JBUI.scale(5), y + JBUI.scale(13))
                }
            } finally {
                g.dispose()
            }
        }
    }

    private inner class ExchangeTableModel(
        private val selectable: Boolean = false,
    ) : AbstractTableModel() {
        private val dataColumns = arrayOf("Time", "Method", "Status", "Host", "Path", "Duration", "Kind")
        private val columns = if (selectable) arrayOf("", *dataColumns) else dataColumns
        private var rows: List<InspectedExchange> = emptyList()
        private val checkedIds = linkedSetOf<String>()

        fun setExchanges(value: List<InspectedExchange>) {
            rows = value
            val visibleIds = value.map { it.correlationId }.toSet()
            checkedIds.retainAll(visibleIds)
            fireTableDataChanged()
        }

        fun checkedExchanges(): List<InspectedExchange> =
            rows.filter { it.correlationId in checkedIds }

        fun checkedCount(): Int = checkedIds.size

        fun selectAllVisible() {
            checkedIds.clear()
            checkedIds.addAll(rows.map { it.correlationId })
            fireTableDataChanged()
        }

        fun clearChecks() {
            if (checkedIds.isEmpty()) return
            checkedIds.clear()
            fireTableDataChanged()
        }

        fun getExchange(sourceTable: JTable, viewRow: Int): InspectedExchange? {
            if (viewRow < 0 || viewRow >= sourceTable.rowCount) return null
            val modelRow = sourceTable.convertRowIndexToModel(viewRow)
            return rows.getOrNull(modelRow)
        }

        fun getExchangeAtModelRow(modelRow: Int): InspectedExchange? = rows.getOrNull(modelRow)

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (selectable && columnIndex == 0) Boolean::class.javaObjectType else Any::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            selectable && columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = rows[rowIndex]
            val dataIndex = if (selectable) columnIndex - 1 else columnIndex
            if (selectable && columnIndex == 0) {
                return item.correlationId in checkedIds
            }
            return when (dataIndex) {
                0 -> timeFormat.format(Date(item.updatedAtMs))
                1 -> item.method
                2 -> item.displayStatus
                3 -> item.host
                4 -> buildString {
                    if (item.pinned) append("★ ")
                    if (item.tags.isNotEmpty()) append("[${item.tags.joinToString(",")}] ")
                    append(item.path.ifBlank { item.url })
                }
                5 -> item.durationMs?.let { "$it ms" } ?: "-"
                6 -> item.kind.name
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (!selectable || columnIndex != 0) return
            val id = rows.getOrNull(rowIndex)?.correlationId ?: return
            if (aValue == true) checkedIds += id else checkedIds -= id
            fireTableCellUpdated(rowIndex, columnIndex)
            updateSelectedCount()
        }
    }

    private inner class ExchangeRowRenderer(
        private val selectable: Boolean,
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val modelRow = table.convertRowIndexToModel(row)
            val exchange = (table.model as? ExchangeTableModel)?.getExchangeAtModelRow(modelRow)
            val dataColumn = if (selectable) column - 1 else column
            font = table.font.deriveFont(if (dataColumn == 1 || dataColumn == 2) Font.BOLD else Font.PLAIN)
            horizontalAlignment = if (dataColumn == 1 || dataColumn == 2 || dataColumn == 5 || dataColumn == 6) {
                SwingConstants.CENTER
            } else {
                SwingConstants.LEFT
            }
            border = JBUI.Borders.empty(0, 7)
            if (!isSelected) {
                val colors = rowColors(exchange)
                background = colors.background
                foreground = when (dataColumn) {
                    1 -> Color.decode("#${methodColor(exchange?.method.orEmpty())}")
                    2 -> Color.decode("#${exchange?.let(::statusColor) ?: "607D8B"}")
                    else -> colors.foreground
                }
            }
            return component
        }
    }

    private data class RowColors(val background: Color, val foreground: Color)

    private fun rowColors(exchange: InspectedExchange?): RowColors {
        val defaultFg = JBColor.namedColor("Table.foreground", JBColor.foreground())
        if (exchange == null) {
            return RowColors(JBColor.namedColor("Table.background", JBColor.background()), defaultFg)
        }
        if (!exchange.errorMessage.isNullOrBlank() || exchange.displayStatus == "ERR") {
            return RowColors(ERROR_BG, defaultFg)
        }
        return when (exchange.kind) {
            ExchangeKind.HTTP -> RowColors(HTTP_BG, defaultFg)
            ExchangeKind.WEBSOCKET -> RowColors(WS_BG, defaultFg)
            ExchangeKind.SSE -> RowColors(SSE_BG, defaultFg)
            ExchangeKind.GRPC -> RowColors(GRPC_BG, defaultFg)
            ExchangeKind.CONNECTION -> RowColors(CONN_BG, defaultFg)
        }
    }

    companion object {
        private val HTTP_BG = JBColor(Color(0xEAF9ED), Color(0x1D3322))
        private val WS_BG = JBColor(Color(0xF3EAF8), Color(0x2A1F35))
        private val SSE_BG = JBColor(Color(0xE7F7F4), Color(0x193330))
        private val GRPC_BG = JBColor(Color(0xEEE9FA), Color(0x28203A))
        private val ERROR_BG = JBColor(Color(0xFDECEC), Color(0x3A1F1F))
        private val CONN_BG = JBColor(Color(0xF0F0F0), Color(0x2A2A2A))
        private val RECORD_HEADER_BG = JBColor(Color(0xDDF6E2), Color(0x203C27))
        private val RECORD_TOOLBAR_BG = JBColor(Color(0xF2FBF4), Color(0x192D1E))
        private val RECORD_BORDER = JBColor(Color(0x47C95E), Color(0x47C95E))
        private val DETAIL_HEADER_BG = JBColor(Color(0xE9E1F6), Color(0x352746))
        private val DETAIL_TOOLBAR_BG = JBColor(Color(0xF7F3FC), Color(0x271F32))
        private val DETAIL_BORDER = JBColor(Color(0xA583C6), Color(0x72528F))
        private val TIMELINE_ROW_BG = JBColor(Color(0xF3F7FA), Color(0x242A30))
        private val HTTP_ACCENT = JBColor(Color(0x47C95E), Color(0x47C95E))
        private val GRPC_ACCENT = JBColor(Color(0x6A1B9A), Color(0xCE93D8))
        private val TEAL_ACCENT = JBColor(Color(0x00796B), Color(0x4DB6AC))
        private val ORANGE_ACCENT = JBColor(Color(0xC65D00), Color(0xFFB74D))
        private val PREVIEW_ACCENT = JBColor(Color(0x7B1FA2), Color(0xBA68C8))
        private val HEALTHY_SOLID = JBColor(Color(0x168344), Color(0x2E9D5B))
        private val DANGER_SOLID = JBColor(Color(0xC2342D), Color(0xB94640))
        private val TAB_INACTIVE_BG = JBColor(Color(0xF3F5F7), Color(0x2B2D30))
        private val BLUE_BUTTON_BG = JBColor(Color(0xE5F8E9), Color(0x233B29))
        private val PURPLE_BUTTON_BG = JBColor(Color(0xF0E7F7), Color(0x3A2B45))
        private val ORANGE_BUTTON_BG = JBColor(Color(0xFFF0DE), Color(0x473424))
        private val RED_BUTTON_BG = JBColor(Color(0xFCE8E7), Color(0x492B2B))
        private val HEALTHY_FG = JBColor(Color(0x15803D), Color(0x69DB7C))
        private val WARNING_FG = JBColor(Color(0xB26A00), Color(0xFFD166))
        private val ERROR_FG = JBColor(Color(0xB3261E), Color(0xFF7B72))
    }
}
