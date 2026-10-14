package kakacat.android.helper.ide.network

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** 调用列表。直接读 [NetStore.calls]，不复制。 */
class CallTableModel(private val store: NetStore) : AbstractTableModel() {

    enum class Column(val title: String, val width: Int, val type: Class<*>) {
        INDEX("#", 44, Integer::class.java),
        TIME("Time", 100, java.lang.Long::class.java),
        KIND("Kind", 48, String::class.java),
        METHOD("Method", 64, String::class.java),
        STATUS("Status", 72, String::class.java),
        HOST("Host", 170, String::class.java),
        NAME("Name", 420, String::class.java),
        DURATION("Duration", 80, java.lang.Long::class.java),
        SIZE("Size", 72, java.lang.Long::class.java),
    }

    private val columns = Column.entries

    override fun getRowCount(): Int = store.calls.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column].title
    override fun getColumnClass(columnIndex: Int): Class<*> = columns[columnIndex].type

    fun callAt(row: Int): NetCall? = store.calls.getOrNull(row)

    fun rowOf(call: NetCall): Int = store.calls.indexOf(call)

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val c = store.calls.getOrNull(rowIndex) ?: return null
        return when (columns[columnIndex]) {
            Column.INDEX -> c.index
            Column.TIME -> c.startTime
            Column.KIND -> c.kind.label
            Column.METHOD -> c.displayMethod
            Column.STATUS -> c.displayStatus
            Column.HOST -> c.displayHost
            Column.NAME -> c.name
            Column.DURATION -> c.durationMs
            Column.SIZE -> c.displaySize
        }
    }

    /** 按列格式化，并把失败的行标红、进行中的行置灰。 */
    inner class Renderer : DefaultTableCellRenderer() {
        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val modelCol = columns[table.convertColumnIndexToModel(column)]
            val call = callAt(table.convertRowIndexToModel(row))
            val text = when (modelCol) {
                Column.TIME -> (value as? Long)?.let { timeFormat.format(Date(it)) } ?: ""
                Column.DURATION -> (value as? Long)?.let { "$it ms" } ?: ""
                Column.SIZE -> (value as? Long)?.takeIf { it > 0 }?.let { BodyFormat.size(it) } ?: ""
                else -> value?.toString() ?: ""
            }
            super.getTableCellRendererComponent(table, text, isSelected, false, row, column)
            horizontalAlignment = when (modelCol) {
                Column.INDEX, Column.DURATION, Column.SIZE -> SwingConstants.RIGHT
                else -> SwingConstants.LEFT
            }
            toolTipText = if (modelCol == Column.NAME) call?.url ?: text else null
            if (!isSelected && call != null) {
                foreground = when {
                    call.isFailure -> JBColor.RED
                    call.state == CallState.PENDING -> SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
                    else -> table.foreground
                }
            }
            return this
        }
    }
}
