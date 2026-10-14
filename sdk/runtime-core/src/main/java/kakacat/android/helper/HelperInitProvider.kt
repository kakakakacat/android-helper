package kakacat.android.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 自动初始化。用 ContentProvider 而不是改业务的 `Application.onCreate()`：
 *
 *  * 使用方零改动；
 *  * provider 在 Application.onCreate 之前就跑，比任何业务请求都早；
 *  * `multiprocess="true"`，多进程时每个进程都会初始化。
 */
class HelperInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        runCatching { Helper.install(ctx) }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
