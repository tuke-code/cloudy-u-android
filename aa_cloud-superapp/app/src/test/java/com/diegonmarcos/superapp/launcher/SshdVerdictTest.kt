package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.launcher.SshdVerdict.V
import com.diegonmarcos.superapp.network.LocalDoors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** #1746 the strip's SSHd verdict equals the webserver's: same shared vectors, then the strip's own probe mapping. */
class SshdVerdictTest {
    private fun vectors(): JSONObject {
        val f = listOf("../../ac_cloud-c3-webserver/data/sshd-verdict-vectors.json", "ac_cloud-c3-webserver/data/sshd-verdict-vectors.json").map(::File).first { it.exists() }
        return JSONObject(f.readText())
    }

    @Test fun `every shared verdict vector`() {
        val cases = vectors().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val a = c.getJSONArray("checks")
            val checks = (0 until a.length()).map { a.getJSONArray(it).let { x -> SshdVerdict.Check(x.getString(0), x.getBoolean(1), if (x.isNull(2)) null else x.getBoolean(2)) } }
            val (v, failed) = SshdVerdict.verdict(checks)
            assertEquals(c.getString("name"), c.getString("verdict"), v.name.lowercase())
            val want = c.getJSONArray("failed").let { w -> (0 until w.length()).map { w.getString(it) } }
            assertEquals(c.getString("name"), want, failed)
        }
    }

    private val door = LocalDoors.Door("webserver", "Cloud C3 Webserver", 8025, null)
    private fun p(state: LocalDoors.State, detail: String = "") = LocalDoors.Probe(door, state, detail, 3, 0)

    @Test fun `loopback up but mesh down is amber naming mesh, both up is green, nothing is red`() {
        val up = p(LocalDoors.State.ON, "SSH-2.0-x")
        assertEquals(V.GREEN, SshdVerdict.ofDoor(up, up, true))
        assertEquals(V.AMBER, SshdVerdict.ofDoor(up, p(LocalDoors.State.OFF, "refused"), true))
        assertEquals(listOf("mesh"), SshdVerdict.verdict(SshdVerdict.checks(up, null, true)).second)
        assertEquals(V.RED, SshdVerdict.ofDoor(p(LocalDoors.State.OFF, "refused (nothing listening)"), null, true))
        assertEquals(V.GREY, SshdVerdict.ofDoor(up, null, false))
        assertEquals(V.GREY, SshdVerdict.ofDoor(null, null, false))
    }

    @Test fun `a port that accepts but sends no banner is partial, not green`() {
        val v = SshdVerdict.ofDoor(p(LocalDoors.State.OFF, "connected, no SSH banner"), p(LocalDoors.State.OFF, "refused"), true)
        assertEquals(V.AMBER, v)
    }

    @Test fun `chip combine and tints`() {
        assertEquals(V.GREEN, SshdVerdict.combine(listOf(V.RED, V.GREEN)))
        assertEquals(V.AMBER, SshdVerdict.combine(listOf(V.RED, V.AMBER)))
        assertEquals(V.RED, SshdVerdict.combine(listOf(V.RED, V.RED)))
        assertEquals(V.GREY, SshdVerdict.combine(emptyList()))
        val on = 0xFFFFFFFF.toInt(); val off = 0x44FFFFFF
        assertEquals(on, StripDoors.tintV(V.GREEN, on, off))
        assertEquals(StripDoors.CHARGE_TINT, StripDoors.tintV(V.AMBER, on, off))
        assertEquals(off, StripDoors.tintV(V.RED, on, off))
        assertEquals(StripDoors.UNKNOWN_TINT, StripDoors.tintV(V.GREY, on, off))
    }
}
