package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.StoreAdopt.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1744 the adopt command, its result parsing, the read-back and the row marker; rows carry no yellow line, the detail keeps the text. */
class StoreAdoptTest {
    private val store = "com.diegonmarcos.cloudstore"

    @Test fun commandIsTheOneAllowlistedShape() {
        assertEquals("pm set-installer com.diegonmarcos.cloudmail $store", StoreAdopt.command("com.diegonmarcos.cloudmail"))
        assertNull(StoreAdopt.command("x; rm -rf /"))
        assertNull(StoreAdopt.command(""))
    }

    @Test fun readBackParsesInstallers() {
        val m = StoreAdopt.parseInstallers("package:com.a.b  installer=com.android.shell\npackage:com.c.d  installer=null\npackage:com.e.f  installer=$store\nnoise\n")
        assertEquals("com.android.shell", m["com.a.b"]); assertNull(m["com.c.d"]); assertTrue("com.c.d" in m); assertEquals(store, m["com.e.f"])
        assertEquals(listOf("com.a.b", "com.c.d"), StoreAdopt.needing(m, listOf("com.e.f", "com.c.d", "com.a.b", "com.gone")))
    }

    @Test fun setResultsAreParsed() {
        assertEquals(State.ADB_DOWN, StoreAdopt.parseSetResult(null).first)
        assertNull(StoreAdopt.parseSetResult("").first)
        assertNull(StoreAdopt.parseSetResult("Success").first)
        assertEquals(State.UNSUPPORTED, StoreAdopt.parseSetResult("Unknown command: set-installer").first)
        assertEquals(State.FAILED, StoreAdopt.parseSetResult("java.lang.SecurityException: not allowed").first)
        assertEquals(State.FAILED, StoreAdopt.parseSetResult("denied by the shell broker: no rule").first)
        assertEquals(State.FAILED, StoreAdopt.parseSetResult("Failure [unknown package]").first)
    }

    @Test fun resolveTrustsTheReadBackNotTheCommand() {
        assertEquals(State.ALREADY, StoreAdopt.resolve("p.q", store, null, store).state)
        assertEquals(State.ADOPTED, StoreAdopt.resolve("p.q", "com.android.shell", "", store).state)
        assertEquals(State.FAILED, StoreAdopt.resolve("p.q", "com.android.shell", "", "com.android.shell").state)   // ran, but nothing changed
        assertEquals(State.ADB_DOWN, StoreAdopt.resolve("p.q", null, null, null).state)
        assertEquals(State.UNSUPPORTED, StoreAdopt.resolve("p.q", null, "Unknown command", null).state)
    }

    @Test fun rowMarkerIsOneSmallToken() {
        assertEquals("", StoreAdopt.marker(null))
        assertEquals(" · silent", StoreAdopt.marker("silent"))
        assertEquals(" · prompts", StoreAdopt.marker("will prompt: installed by an unknown source: Android asks once to move it to Cloud Store, then updates are silent"))
        assertEquals(" · prompts", StoreAdopt.marker("will prompt: targetSdk 33 is below 35"))
    }

    // source-level guard (no Compose host in this module): the row draws no Next update line, the detail view keeps it
    @Test fun rowsDrawNoYellowLineAndTheDetailKeepsTheText() {
        val f = listOf("src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt",
            "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt").map(::File).first { it.isFile }
        val s = f.readText()
        assertTrue("no store-silent row line", !s.contains("store-silent:") && !s.contains("SilentArea"))
        assertTrue("detail keeps the full text", s.contains("store-detail-next-update:") && s.contains("\"Next update: \$line\""))
        assertTrue("marker in the status line", s.contains("StoreAdopt.marker("))
        // every tab's package rows are the one FleetRow, so no tab can draw its own line
        assertEquals(1, Regex("""StoreRow\.Item\(app\.label""").findAll(s).count())
    }
}
