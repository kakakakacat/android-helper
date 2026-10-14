package kakacat.android.helper.ide

import com.google.gson.JsonParser
import kakacat.android.helper.client.layout.LayoutSnapshot
import kakacat.android.helper.ide.layout.SourceNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutModelTest {

    private val json = """
        {"type":"layout.snapshot.result","req":1,
         "screen":{"w":640,"h":960,"density":2.0},
         "costMs":{"tree":10,"image":20,"nodes":5},
         "windows":[
           {"index":0,"title":"main","activity":"com.foo.MainActivity","x":0,"y":0,"w":640,"h":960,
            "root":{"id":0,"cls":"com.android.internal.policy.DecorView","x":0,"y":0,"w":640,"h":960,"vis":"V","children":[
              {"id":1,"cls":"android.widget.LinearLayout","x":0,"y":100,"w":640,"h":800,"vis":"V",
               "src":{"layout":"activity_main","inflated":"com.foo.MainActivity.onCreate(MainActivity.kt:32)"},
               "children":[
                 {"id":2,"cls":"android.widget.Button","rid":"ok","x":40,"y":200,"w":200,"h":80,"vis":"V","text":"OK",
                  "src":{"click":"com.foo.MainActivity.button(MainActivity.kt:66)"}},
                 {"id":3,"cls":"android.widget.TextView","x":40,"y":200,"w":200,"h":80,"vis":"G"}
               ]}
            ]}},
           {"index":1,"title":"dialog","x":100,"y":300,"w":440,"h":300,
            "root":{"id":4,"cls":"com.android.internal.policy.DecorView","x":100,"y":300,"w":440,"h":300,"vis":"V"}}
         ]}
    """.trimIndent()

    @Test
    fun parsesTreeAndPicks() {
        val snap = LayoutSnapshot.parse(JsonParser.parseString(json).asJsonObject)
        assertEquals(2, snap.windows.size)
        assertEquals(2f, snap.density)
        val button = snap.findById(2)!!
        assertEquals("Button", button.simpleName)
        assertEquals(listOf(0, 0), button.path)
        assertSame(button, snap.findByPath(0, listOf(0, 0)))
        assertEquals("com.foo.MainActivity.button(MainActivity.kt:66)", button.sources["click"])

        // 点在按钮上：最上层窗口（dialog）不包含该点，回到主窗口；GONE 的 TextView 被忽略
        assertSame(button, snap.pick(50, 210, includeHidden = false))
        // 同一点再点一次往外一层
        assertEquals(1, snap.pick(50, 210, includeHidden = false, skip = 1)!!.id)
        // dialog 窗口在上面，挡住了下面的主窗口
        assertEquals(4, snap.pick(150, 350, includeHidden = false)!!.id)
    }

    @Test
    fun parsesSites() {
        val s = SourceNavigator.Site.parse("kakacat.android.helper.sample.MainActivity.button(MainActivity.kt:63)")!!
        assertEquals("kakacat.android.helper.sample.MainActivity", s.className)
        assertEquals("button", s.method)
        assertEquals("MainActivity.kt", s.file)
        assertEquals(63, s.line)
        assertEquals("kakacat/android/helper/sample", s.packagePath)

        val lambda = SourceNavigator.Site.parse("com.foo.Bar\$Inner.onCreate\$lambda\$0(Bar.java:7)")!!
        assertEquals("com.foo.Bar\$Inner", lambda.className)
        assertEquals(7, lambda.line)

        assertEquals(0, SourceNavigator.Site.parse("com.foo.Bar.baz(Bar.kt)")!!.line)
        assertNull(SourceNavigator.Site.parse("garbage"))
    }
}
