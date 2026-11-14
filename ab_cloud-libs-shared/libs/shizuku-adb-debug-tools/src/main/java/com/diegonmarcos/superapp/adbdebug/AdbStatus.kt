package com.diegonmarcos.superapp.adbdebug

/** #1743 State of one step of the chain, as the Status page draws it. OK_CLOSED = ok, and closed on purpose ("closed after launch"). */
enum class StepStatus { OK, OK_CLOSED, RUNNING, FAILED, SKIPPED, UNKNOWN }

/** The one action that fixes a step (its button); the Android side maps each to the exact screen or run. NONE = no button. */
enum class StepFix(val label: String) {
    NONE(""), PERMS("Perms"), ABOUT_PHONE("About phone"), DEV_OPTIONS("Open"), WIRELESS_DEBUGGING("Open"), WIFI("Wi-Fi"),
    PAIR("Pair"), CONNECT("Connect"), SHIZUKU("Open Shizuku"), CLIENTS("Clients"),
}

/** One line of the Status page's step list. [at] = epoch ms of the reading it was drawn from. */
data class StatusStep(val id: String, val label: String, val status: StepStatus, val reason: String, val at: Long, val fix: StepFix = StepFix.NONE)

/** The verdict every surface shows. [closedByDesign] = the embedded session is closed after the launch (Local server profile). */
data class StatusVerdict(val state: ConnState, val dot: Dot, val title: String, val detail: String, val route: String?, val closedByDesign: Boolean)

/**
 * #1743 THE status model: one pure function of [ChannelFacts] for the Status page's header card, the step list, the buttons, the
 * Home table, the chip and (through AdbLink.classify) the Store chip. No surface derives its own verdict.
 */
object AdbStatus {
    const val S_PERMS = "perms"
    const val S_DEV = "dev-options"
    const val S_WD = "wireless-debugging"
    const val S_PAIRED = "paired"
    const val S_EMBEDDED = "embedded-connect"
    const val S_SERVER = "local-server-launched"
    const val S_ROUNDTRIP = "round-trip"
    const val S_SHIZUKU = "shizuku"
    const val S_PROFILE = "profile"
    const val S_CLIENTS = "clients"

    /** The rows, in order (Shizuku only appears in the Shizuku and Auto profiles). */
    val STEP_IDS = listOf(S_PERMS, S_DEV, S_WD, S_PAIRED, S_EMBEDDED, S_SERVER, S_ROUNDTRIP, S_SHIZUKU, S_PROFILE, S_CLIENTS)

    fun verdict(f: ChannelFacts): StatusVerdict {
        val state = ChannelState.connState(f)
        val route = ChannelState.route(f)
        val closed = ChannelState.localCarries(f)
        val dot = when (state) { ConnState.UP -> Dot.OK; ConnState.DEGRADED, ConnState.CONNECTING -> Dot.WARN; ConnState.DOWN -> Dot.BAD }
        val title = when (state) { ConnState.UP -> "Connected"; ConnState.DEGRADED -> "Degraded"; ConnState.CONNECTING -> "Connecting"; ConnState.DOWN -> "Disconnected" }
        val detail = when (state) {
            ConnState.UP -> "via $route" + (f.serverUid?.takeIf { route == ChannelSelector.LOCAL }?.let { " ($it)" } ?: "") +
                if (closed) "; the embedded adb session is closed after the launch, by design (pairing kept)" else ""
            ConnState.DEGRADED -> ChannelState.degradedWhy(f)
            ConnState.CONNECTING -> "the chain is running"
            ConnState.DOWN -> "no channel answers in ${f.mode.title()} mode; Connect runs the whole chain"
        }
        return StatusVerdict(state, dot, title, detail, route, closed)
    }

    /** Connect / Disconnect / Reconnect: nothing greyed that is usable, every grey button says why. */
    fun buttons(f: ChannelFacts): Map<StatusButton, Enablement> {
        val state = ChannelState.connState(f)
        if (state == ConnState.CONNECTING) {
            val busy = Enablement(false, "a connect is running")
            return StatusButton.values().associateWith { busy }
        }
        val en = ChannelActions.enablement(f)
        return mapOf(
            StatusButton.CONNECT to if (state == ConnState.UP) Enablement(false, "already connected (Reconnect runs the chain again)") else Enablement(true),
            StatusButton.DISCONNECT to en.getValue(ChannelAction.DISCONNECT),
            StatusButton.RECONNECT to Enablement(true),
        )
    }

    private fun shellUid(uid: String?) = uid == null || uid == "uid 2000" || uid == "uid 0"

    /** The chain as live rows. [permsMissing] null = not read; [clients] null = not read; [running] / [failed] = the last run's live steps. */
    fun steps(
        f: ChannelFacts, permsMissing: List<String>?, clients: List<String>?,
        running: Set<StepId> = emptySet(), failed: Map<StepId, String> = emptyMap(),
    ): List<StatusStep> {
        val at = f.probedAt
        val adbMode = f.mode != ChannelMode.SHIZUKU
        val mt = f.mode.title()
        val v = verdict(f)
        val out = ArrayList<StatusStep>()
        fun add(id: String, label: String, st: StepStatus, why: String, fix: StepFix = StepFix.NONE) { out += StatusStep(id, label, st, why, at, fix) }
        fun skip(id: String, label: String) = add(id, label, StepStatus.SKIPPED, "not used in $mt mode")

        when {
            permsMissing == null -> add(S_PERMS, "Perms", StepStatus.UNKNOWN, "not read yet")
            permsMissing.isEmpty() -> add(S_PERMS, "Perms", StepStatus.OK, "all required permissions granted")
            else -> add(S_PERMS, "Perms", StepStatus.FAILED, "${permsMissing.size} missing: " + permsMissing.take(3).joinToString(", ") + if (permsMissing.size > 3) "..." else "", StepFix.PERMS)
        }
        if (!adbMode) { skip(S_DEV, "Developer options"); skip(S_WD, "Wireless debugging"); skip(S_PAIRED, "Paired") } else {
            when (f.devOptions) {
                true -> add(S_DEV, "Developer options", StepStatus.OK, "enabled")
                false -> add(S_DEV, "Developer options", StepStatus.FAILED, "off: Settings > About phone > tap Build number seven times", StepFix.ABOUT_PHONE)
                null -> add(S_DEV, "Developer options", StepStatus.UNKNOWN, "the device would not say", StepFix.DEV_OPTIONS)
            }
            when {
                f.wirelessDebug == true && f.onWifi == false -> add(S_WD, "Wireless debugging", StepStatus.FAILED, "on, but the phone is not on Wi-Fi", StepFix.WIFI)
                f.wirelessDebug == true -> add(S_WD, "Wireless debugging", StepStatus.OK, "on")
                f.wirelessDebug == false -> add(S_WD, "Wireless debugging", StepStatus.FAILED, "off: turn it on in Developer options", StepFix.WIRELESS_DEBUGGING)
                else -> add(S_WD, "Wireless debugging", StepStatus.UNKNOWN, "the device would not say", StepFix.WIRELESS_DEBUGGING)
            }
            if (f.adbPaired) add(S_PAIRED, "Paired", StepStatus.OK, "paired once; reconnects by itself")
            else add(S_PAIRED, "Paired", StepStatus.FAILED, "not paired: type the 6-digit code into the notification", StepFix.PAIR)
        }
        // Embedded connect: in Local server mode "closed after launch" is the expected, healthy end state.
        if (!adbMode) skip(S_EMBEDDED, "Embedded connect") else when {
            f.adbConnected -> add(S_EMBEDDED, "Embedded connect", StepStatus.OK, "connected" + (f.adbEndpoint?.let { " $it" } ?: ""))
            ChannelState.sessionOptional(f) && f.serverRunning && shellUid(f.serverUid) ->
                add(S_EMBEDDED, "Embedded connect", StepStatus.OK_CLOSED, "closed after launch (expected in Local server mode, pairing kept)")
            f.adbPaired -> add(S_EMBEDDED, "Embedded connect", StepStatus.FAILED, "paired, not connected", StepFix.CONNECT)
            else -> add(S_EMBEDDED, "Embedded connect", StepStatus.FAILED, "needs pairing first", StepFix.PAIR)
        }
        if (!f.ownsServer || (f.mode != ChannelMode.LOCAL_SERVER && f.mode != ChannelMode.AUTO))
            add(S_SERVER, "Local server launched", StepStatus.SKIPPED, if (!f.ownsServer) "this app runs no server of its own" else "not used in $mt mode")
        else when {
            f.serverRunning && !shellUid(f.serverUid) -> add(S_SERVER, "Local server launched", StepStatus.FAILED, "answers as ${f.serverUid}, not the shell (uid 2000)", StepFix.CONNECT)
            f.serverRunning -> add(S_SERVER, "Local server launched", StepStatus.OK, listOfNotNull("127.0.0.1:${f.serverPort}", f.serverUid, f.serverUptime?.let { "up $it" }, f.serverPid?.let { "pid $it" }).joinToString(", "))
            else -> add(S_SERVER, "Local server launched", StepStatus.FAILED, "no answer on 127.0.0.1:${f.serverPort}", StepFix.CONNECT)
        }
        val r = v.route
        if (r != null) add(S_ROUNDTRIP, "Round trip", StepStatus.OK, "answered via $r" + (f.serverUid?.takeIf { r == ChannelSelector.LOCAL }?.let { " ($it)" } ?: ""))
        else add(S_ROUNDTRIP, "Round trip", StepStatus.FAILED, "no channel answered a shell command", StepFix.CONNECT)
        if (f.mode == ChannelMode.SHIZUKU || f.mode == ChannelMode.AUTO) {
            when (f.shizuku) {
                ShizukuState.UP -> add(S_SHIZUKU, "Shizuku", StepStatus.OK, "running and permitted" + (f.shizukuVersion?.let { " $it" } ?: ""))
                ShizukuState.PERMISSION_NEEDED -> add(S_SHIZUKU, "Shizuku", StepStatus.FAILED, "running, permission needed", StepFix.SHIZUKU)
                ShizukuState.NOT_RUNNING -> add(S_SHIZUKU, "Shizuku", StepStatus.FAILED, "installed, not running", StepFix.SHIZUKU)
                ShizukuState.NOT_INSTALLED -> add(S_SHIZUKU, "Shizuku", StepStatus.FAILED, "not installed", StepFix.SHIZUKU)
            }
        }
        add(S_PROFILE, "Profile", StepStatus.OK, "$mt: ${v.title} (${v.detail})")
        if (clients == null) add(S_CLIENTS, "Clients using it", StepStatus.UNKNOWN, "not read yet")
        else add(S_CLIENTS, "Clients using it", StepStatus.OK,
            if (clients.isEmpty()) "none allowed yet" else "${clients.size} allowed: " + clients.joinToString(", ") { it.removePrefix("com.diegonmarcos.") }, if (clients.isEmpty()) StepFix.CLIENTS else StepFix.NONE)

        // Live overlay from the last Connect run: a running step shows running, a failed one shows its exact reason.
        val rowOf = mapOf(StepId.DEV_OPTIONS to S_DEV, StepId.WIRELESS_DEBUG to S_WD, StepId.PAIR to S_PAIRED,
            StepId.ADB_CONNECT to S_EMBEDDED, StepId.LAUNCH_SERVER to S_SERVER, StepId.VERIFY to S_ROUNDTRIP)
        return out.map { s ->
            val sid = rowOf.entries.firstOrNull { it.value == s.id }?.key
            when {
                sid != null && sid in running -> s.copy(status = StepStatus.RUNNING, reason = "running...", fix = StepFix.NONE)
                sid != null && failed[sid] != null && s.status != StepStatus.OK && s.status != StepStatus.OK_CLOSED -> s.copy(status = StepStatus.FAILED, reason = failed.getValue(sid))
                else -> s
            }
        }
    }
}
