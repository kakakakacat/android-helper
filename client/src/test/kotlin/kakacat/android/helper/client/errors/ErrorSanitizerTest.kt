package kakacat.android.helper.client.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSanitizerTest {

    private val stack = """
        java.lang.IllegalStateException: failed to load https://api.example.com/v1/user?token=abc for com.secret.app
        	at kakacat.android.helper.layout.Screenshots.start(Screenshots.kt:71)
        	at com.secret.app.ui.HomeActivity.onCreate(HomeActivity.kt:42)
        	at com.secret.app.ui.HomeActivity${'$'}inner.run(HomeActivity.kt:50)
        	at android.app.Activity.performCreate(Activity.java:8000)
        Caused by: com.secret.app.InternalException: user 12345678 at /data/user/0/com.secret.app/files/x.db
        	at com.secret.app.Db.open(Db.kt:9)
        	... 3 more
    """.trimIndent()

    @Test
    fun appFramesAndNamesAreRemoved() {
        val s = ErrorSanitizer.stack(stack, "com.secret.app")
        assertFalse(s, s.contains("secret"))
        assertFalse(s, s.contains("example.com"))
        assertFalse(s, s.contains("12345678"))
        assertTrue(s, s.contains("at kakacat.android.helper.layout.Screenshots.start(Screenshots.kt:71)"))
        assertTrue(s, s.contains("at android.app.Activity.performCreate"))
        // 连续两帧 App 代码合成一行
        assertEquals(s, 2, s.lines().count { it.trim() == "at <app>" })
        assertTrue(s, s.contains("Caused by: <app exception>: user <id> at <path>"))
    }

    @Test
    fun fingerprintIgnoresLineNumbersAndAppFrames() {
        val a = ErrorSanitizer.stack(stack, "com.secret.app")
        val b = ErrorSanitizer.stack(stack.replace("Screenshots.kt:71", "Screenshots.kt:99").replace("com.secret.app", "org.other"), "org.other")
        assertEquals(
            ErrorSanitizer.fingerprint("layout.screenshot.window", "java.lang.IllegalStateException", a),
            ErrorSanitizer.fingerprint("layout.screenshot.window", "java.lang.IllegalStateException", b),
        )
    }
}
