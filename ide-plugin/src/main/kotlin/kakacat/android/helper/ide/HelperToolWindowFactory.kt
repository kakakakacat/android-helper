package kakacat.android.helper.ide

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import kakacat.android.helper.ide.adb.Device
import kakacat.android.helper.ide.adb.Endpoint
import kakacat.android.helper.ide.layout.LayoutPanel
import kakacat.android.helper.ide.network.NetworkPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class HelperToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val session = HelperSession(project)
        val panel = HelperToolWindow(project, session)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(session)
        toolWindow.contentManager.addContent(content)
        panel.refreshDevices()
    }
}

/**
 * 顶部：设备 / App 选择 + 连接控制（所有标签页共用一条连接）。
 * 下面：功能标签页。
 */
class HelperToolWindow(private val project: Project, private val session: HelperSession) :
    JPanel(BorderLayout()), HelperSession.Listener {

    private val devices = ComboBox<Device>()
    private val apps = ComboBox<Endpoint>()
    private val status = JBLabel().apply { border = JBUI.Borders.emptyLeft(8) }

    @Volatile
    private var loading = false

    init {
        apps.setMinimumAndPreferredWidth(JBUI.scale(260))
        devices.setMinimumAndPreferredWidth(JBUI.scale(220))
        devices.addActionListener { if (!loading) refreshApps() }

        val group = DefaultActionGroup().apply {
            add(action(HelperBundle.message("action.refresh"), AllIcons.Actions.Refresh) { refreshDevices() })
            add(object : AnAction(HelperBundle.message("action.connect"), HelperBundle.message("action.connect.desc"), AllIcons.Actions.Execute), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    (apps.selectedItem as? Endpoint)?.let { session.connect(it) }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = apps.selectedItem != null &&
                        (session.state == HelperSession.State.DISCONNECTED ||
                            session.endpoint != apps.selectedItem)
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction(HelperBundle.message("action.disconnect"), HelperBundle.message("action.disconnect.desc"), AllIcons.Actions.Suspend), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = session.disconnect()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = session.state != HelperSession.State.DISCONNECTED
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(action(HelperBundle.message("action.clear"), AllIcons.Actions.GC) { session.clear() })
            add(object : AnAction(HelperBundle.message("action.probe"), HelperBundle.message("action.probe.desc"), AllIcons.Actions.Lightning), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = probe()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = session.state == HelperSession.State.CONNECTED
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AndroidHelper.Connection", group, true)
        toolbar.targetComponent = this

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor())
            add(devices)
            add(apps)
            add(toolbar.component)
            add(status)
        }
        add(bar, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        val layout = LayoutPanel(session)
        tabs.addTab(HelperBundle.message("tab.network"), NetworkPanel(session))
        tabs.addTab(HelperBundle.message("tab.layout"), layout)
        tabs.addChangeListener { if (tabs.selectedComponent === layout) layout.onShown() }
        // 开发用：-Dandroidhelper.demoTab=Layout
        System.getProperty("androidhelper.demoTab")?.let { title ->
            (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title }?.let { tabs.selectedIndex = it }
        }
        add(tabs, BorderLayout.CENTER)

        session.addListener(this)
        onStateChanged(session.state, session.statusMessage)
    }

    override fun onStateChanged(state: HelperSession.State, message: String?) {
        status.text = message ?: when (state) {
            HelperSession.State.DISCONNECTED -> HelperBundle.message("status.disconnected")
            else -> ""
        }
        status.foreground = when (state) {
            HelperSession.State.CONNECTED -> JBUI.CurrentTheme.Label.foreground()
            else -> JBUI.CurrentTheme.Label.disabledForeground()
        }
    }

    fun refreshDevices() {
        val adb = session.adb() ?: run {
            status.text = HelperBundle.message("status.adbMissing")
            return
        }
        status.text = HelperBundle.message("status.findingDevices")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { adb.devices() }
            SwingUtilities.invokeLater {
                result.onFailure { status.text = HelperBundle.message("status.adbDevicesFailed", it.message ?: "") }
                result.onSuccess { list ->
                    val previous = devices.selectedItem as? Device
                    loading = true
                    devices.model = DefaultComboBoxModel(list.toTypedArray())
                    list.firstOrNull { it.serial == previous?.serial }?.let { devices.selectedItem = it }
                        ?: list.firstOrNull { it.online }?.let { devices.selectedItem = it }
                    loading = false
                    if (list.isEmpty()) {
                        apps.model = DefaultComboBoxModel()
                        status.text = HelperBundle.message("status.noDevices")
                    } else {
                        refreshApps()
                    }
                }
            }
        }
    }

    private fun refreshApps() {
        val device = devices.selectedItem as? Device ?: return
        val adb = session.adb() ?: return
        if (!device.online) {
            apps.model = DefaultComboBoxModel()
            status.text = HelperBundle.message("status.deviceState", device.state)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { adb.endpoints(device.serial) }
            SwingUtilities.invokeLater {
                result.onFailure { status.text = HelperBundle.message("status.listAppsFailed", it.message ?: "") }
                result.onSuccess { list ->
                    val previous = (apps.selectedItem as? Endpoint) ?: session.endpoint
                    apps.model = DefaultComboBoxModel(list.toTypedArray())
                    list.firstOrNull { it.pkg == previous?.pkg }?.let { apps.selectedItem = it }
                    // 上次连过的 App 还在跑：直接连上，省一次点击
                    val last = session.lastPackage
                    val auto = list.firstOrNull { it.pkg == last }
                    if (auto != null && session.state == HelperSession.State.DISCONNECTED) {
                        apps.selectedItem = auto
                        session.connect(auto)
                        return@onSuccess
                    }
                    if (session.state == HelperSession.State.DISCONNECTED) {
                        status.text = if (list.isEmpty()) {
                            HelperBundle.message("status.noApps")
                        } else {
                            HelperBundle.message("status.appsFound", list.size.toString())
                        }
                    }
                }
            }
        }
    }

    private fun probe() {
        val endpoint = session.endpoint ?: return
        val adb = session.adb() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = runCatching { adb.probe(endpoint) }.getOrNull()
            if (r == null || !r.ok || r.stdout.contains("Error", ignoreCase = true)) {
                NotificationGroupManager.getInstance().getNotificationGroup("Android Helper")
                    .createNotification(HelperBundle.message("probe.failed", r?.stderr?.ifBlank { r.stdout } ?: HelperBundle.message("probe.adbFailed")), NotificationType.WARNING)
                    .notify(project)
            }
        }
    }

    private fun action(text: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, null, icon), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
}
