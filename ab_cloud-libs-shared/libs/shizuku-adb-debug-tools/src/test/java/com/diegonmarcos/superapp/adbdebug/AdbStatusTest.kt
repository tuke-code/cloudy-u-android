package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.shellclient.AdbLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1743 one verdict per profile, buttons by state, single-source consistency, the step list (parity with the old cards). */
class AdbStatusTest {
    private fun base(mode: ChannelMode) = ChannelFacts(mode, 1_000L, devOptions = true, wirelessDebug = true, onWifi = true, notificationsAllowed = true,
        adbPaired = true, adbConnected = false, serverPort = 38099)

    /** The exact phone log: dev ok, wireless ok, paired, embedded connect ok, server launched, round trip ok via uid 2000, embedded session closed. */
    private val phoneAfterChain = base(ChannelMode.LOCAL_SERVER).copy(serverRunning = true, serverUid = "uid 2000", serverUptime = "10s", serverPid = "4242")

    @Test fun theReportedLogSequenceIsConnectedNotDegraded() {
        val v = AdbStatus.verdict(phoneAfterChain)
        assertEquals(ConnState.UP, v.state); assertEquals(Dot.OK, v.dot); assertEquals("Connected", v.title)
        assertEquals("local-server", v.route); assertTrue(v.closedByDesign)
        assertTrue(v.detail, v.detail.contains("closed after the launch, by design"))
        assertFalse(v.detail.contains("adbd connection is down"))
    }

    @Test fun verdictPerProfile() {
        // Local server
        assertEquals(ConnState.DOWN, AdbStatus.verdict(phoneAfterChain.copy(serverRunning = false, serverUid = null)).state)
        assertEquals("Disconnected", AdbStatus.verdict(phoneAfterChain.copy(serverRunning = false, serverUid = null)).title)
        assertEquals(ConnState.DEGRADED, AdbStatus.verdict(phoneAfterChain.copy(serverUid = "uid 10123")).state)   // a real partial failure
        assertEquals(ConnState.CONNECTING, AdbStatus.verdict(phoneAfterChain.copy(connecting = true)).state)
        assertEquals(ConnState.UP, AdbStatus.verdict(phoneAfterChain.copy(adbConnected = true)).state)
        // Embedded: needs the live session; a server is not its channel
        val emb = base(ChannelMode.EMBEDDED_ONLY)
        assertEquals(ConnState.DOWN, AdbStatus.verdict(emb.copy(serverRunning = true, serverUid = "uid 2000")).state)
        assertEquals(ConnState.UP, AdbStatus.verdict(emb.copy(adbConnected = true)).state)
        assertFalse(AdbStatus.verdict(emb.copy(adbConnected = true)).closedByDesign)
        // Shizuku: its own binder round trip
        val shz = base(ChannelMode.SHIZUKU).copy(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true, shizukuAnswers = true)
        assertEquals(ConnState.UP, AdbStatus.verdict(shz).state)
        assertEquals(ConnState.DOWN, AdbStatus.verdict(shz.copy(shizukuAnswers = false)).state)
        // Auto: the server carrying with the session closed is healthy
        assertEquals(ConnState.UP, AdbStatus.verdict(phoneAfterChain.copy(mode = ChannelMode.AUTO)).state)
    }

    @Test fun buttonsByState() {
        val connected = AdbStatus.buttons(phoneAfterChain)
        assertFalse(connected.getValue(StatusButton.CONNECT).enabled); assertTrue(connected.getValue(StatusButton.CONNECT).why!!.contains("already connected"))
        assertTrue(connected.getValue(StatusButton.DISCONNECT).enabled)     // stops the local server even though the embedded session is closed
        assertTrue(connected.getValue(StatusButton.RECONNECT).enabled)
        val down = AdbStatus.buttons(phoneAfterChain.copy(serverRunning = false, serverUid = null))
        assertTrue(down.getValue(StatusButton.CONNECT).enabled)
        assertFalse(down.getValue(StatusButton.DISCONNECT).enabled); assertTrue(down.getValue(StatusButton.DISCONNECT).why!!.isNotBlank())
        assertTrue(down.getValue(StatusButton.RECONNECT).enabled)
        val busy = AdbStatus.buttons(phoneAfterChain.copy(connecting = true))
        assertTrue(busy.values.none { it.enabled } && busy.values.all { it.why == "a connect is running" })
        // degraded: Connect and Reconnect and Disconnect all usable
        val deg = AdbStatus.buttons(phoneAfterChain.copy(serverUid = "uid 10123"))
        assertTrue(deg.getValue(StatusButton.CONNECT).enabled && deg.getValue(StatusButton.DISCONNECT).enabled && deg.getValue(StatusButton.RECONNECT).enabled)
        // every disabled button, in every profile and state, says why
        for (m in ChannelMode.values()) for (srv in listOf(true, false)) for (conn in listOf(true, false)) for (c in listOf(true, false)) {
            val f = base(m).copy(serverRunning = srv, serverUid = if (srv) "uid 2000" else null, adbConnected = conn, connecting = c)
            AdbStatus.buttons(f).forEach { (b, e) -> assertTrue("$b in $m", e.enabled || !e.why.isNullOrBlank()) }
        }
    }

    @Test fun oneVerdictEverywhere() {
        // the chip, the cockpit verdict, the peers dot and the Store/SuperApp AdbLink are all derived from the same facts
        for (m in ChannelMode.values()) for (srv in listOf(true, false)) for (conn in listOf(true, false)) for (uid in listOf("uid 2000", "uid 10123")) {
            val f = base(m).copy(serverRunning = srv, serverUid = if (srv) uid else null, adbConnected = conn,
                shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true, shizukuAnswers = true)
            val v = AdbStatus.verdict(f)
            assertEquals(v.state, ChannelState.connState(f))
            assertEquals(v.state, ChannelState.rows(f).last().let { r -> when (r.dot) { Dot.OK -> ConnState.UP; Dot.BAD -> ConnState.DOWN; else -> ConnState.DEGRADED } })
            assertEquals(AdbPeers.linkOf(v.state), AdbPeers.linkOf(ChannelState.connState(f)))
            // the Store / SuperApp transport link says "connected" for every state the verdict calls Connected in the Local profile
            if (m == ChannelMode.LOCAL_SERVER && v.state == ConnState.UP) {
                val link = AdbLink.classify(null, f.wirelessDebug, f.adbPaired, f.adbConnected, ChannelState.sessionOptional(f), ChannelState.localCarries(f))
                assertTrue("$f -> $link", link == AdbLink.CONNECTED)
            }
        }
        val closed = phoneAfterChain
        assertEquals(AdbLink.CONNECTED, AdbLink.classify(null, true, true, closed.adbConnected, ChannelState.sessionOptional(closed), ChannelState.localCarries(closed)))
        // degraded is never green on the link
        val deg = phoneAfterChain.copy(serverUid = "uid 10123")
        assertEquals(AdbLink.UNREACHABLE, AdbLink.classify(null, true, true, false, ChannelState.sessionOptional(deg), ChannelState.localCarries(deg)))
        assertEquals("ADB Shell: up (local-server)", ChannelState.chip(closed))
    }

    private fun step(l: List<StatusStep>, id: String) = l.first { it.id == id }

    @Test fun stepListShowsTheWholeChainWithLiveState() {
        val l = AdbStatus.steps(phoneAfterChain, emptyList(), listOf("com.diegonmarcos.cloudstore"))
        assertEquals(listOf("perms", "dev-options", "wireless-debugging", "paired", "embedded-connect", "local-server-launched", "round-trip", "profile", "clients"), l.map { it.id })
        assertEquals(StepStatus.OK_CLOSED, step(l, "embedded-connect").status)
        assertTrue(step(l, "embedded-connect").reason.contains("closed after launch"))
        assertEquals(StepStatus.OK, step(l, "local-server-launched").status)
        assertTrue(step(l, "round-trip").reason.contains("uid 2000"))
        assertTrue(step(l, "profile").reason.startsWith("Local server: Connected"))
        assertTrue(step(l, "clients").reason.contains("cloudstore"))
        assertTrue(l.none { it.status == StepStatus.FAILED })
        assertTrue(l.all { it.at == 1_000L })
    }

    @Test fun stepListFailuresCarryTheExactReasonAndTheOneFix() {
        val f = base(ChannelMode.LOCAL_SERVER).copy(devOptions = false, wirelessDebug = false, adbPaired = false)
        val l = AdbStatus.steps(f, listOf("Notifications", "Write secure settings"), null)
        assertEquals(StepFix.PERMS, step(l, "perms").fix); assertTrue(step(l, "perms").reason.startsWith("2 missing"))
        assertEquals(StepFix.ABOUT_PHONE, step(l, "dev-options").fix)
        assertEquals(StepFix.WIRELESS_DEBUGGING, step(l, "wireless-debugging").fix)
        assertEquals(StepFix.PAIR, step(l, "paired").fix)
        assertEquals(StepStatus.FAILED, step(l, "embedded-connect").status)
        assertEquals(StepFix.CONNECT, step(l, "round-trip").fix); assertEquals(StepStatus.FAILED, step(l, "round-trip").status)
        assertEquals(StepStatus.UNKNOWN, step(l, "clients").status)
        // a live run overlays running / failed
        val live = AdbStatus.steps(phoneAfterChain.copy(serverRunning = false, serverUid = null), emptyList(), emptyList(),
            running = setOf(StepId.ADB_CONNECT), failed = mapOf(StepId.LAUNCH_SERVER to "nothing answered on its port"))
        assertEquals(StepStatus.RUNNING, step(live, "embedded-connect").status)
        assertEquals("nothing answered on its port", step(live, "local-server-launched").reason)
    }

    @Test fun stepListByProfile() {
        val shz = base(ChannelMode.SHIZUKU).copy(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true, shizukuAnswers = true)
        val l = AdbStatus.steps(shz, emptyList(), emptyList())
        for (id in listOf("dev-options", "wireless-debugging", "paired", "embedded-connect", "local-server-launched")) assertEquals(id, StepStatus.SKIPPED, step(l, id).status)
        assertEquals(StepStatus.OK, step(l, "shizuku").status); assertEquals(StepStatus.OK, step(l, "round-trip").status)
        val emb = AdbStatus.steps(base(ChannelMode.EMBEDDED_ONLY), emptyList(), emptyList())
        assertEquals(StepStatus.SKIPPED, step(emb, "local-server-launched").status)
        assertEquals(StepStatus.FAILED, step(emb, "embedded-connect").status)   // closed is only "ok" in Local server mode
        assertNull(emb.firstOrNull { it.id == "shizuku" })
    }

    /** Parity (rule 16): every field of the old STATUS card, the old cockpit cards and the Home table still has a row or a home. */
    @Test fun everyOldFieldHasARow() {
        val l = AdbStatus.steps(phoneAfterChain, emptyList(), emptyList())
        val ids = l.map { it.id }.toSet()
        // old Status card / cockpit cards -> step ids
        val moved = mapOf("Perms" to "perms", "Developer Options Flipped" to "dev-options", "Wireless Debugging Flipped" to "wireless-debugging",
            "Pairing" to "paired", "Embedded Connected" to "embedded-connect", "Loopback server" to "local-server-launched",
            "Connection / ADB connected line" to "round-trip", "Mode" to "profile", "Home: Clients" to "clients")
        for ((old, id) in moved) assertTrue("$old -> $id", id in ids)
        assertTrue(AdbStatus.STEP_IDS.containsAll(ids))
    }
}
