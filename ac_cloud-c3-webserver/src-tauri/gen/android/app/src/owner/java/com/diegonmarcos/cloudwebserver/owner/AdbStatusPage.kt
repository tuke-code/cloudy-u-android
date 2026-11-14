package com.diegonmarcos.cloudwebserver.owner

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.ui.Act
import com.diegonmarcos.cloudwebserver.ui.Acts
import com.diegonmarcos.cloudwebserver.ui.DNote
import com.diegonmarcos.cloudwebserver.ui.DRow
import com.diegonmarcos.cloudwebserver.ui.DenseCard
import com.diegonmarcos.superapp.adbdebug.AdbStatus
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.adbdebug.ChannelReader
import com.diegonmarcos.superapp.adbdebug.LocalServerAutoStart
import com.diegonmarcos.superapp.adbdebug.SetupAction
import com.diegonmarcos.superapp.adbdebug.StatusButton
import com.diegonmarcos.superapp.adbdebug.StatusStep
import com.diegonmarcos.superapp.adbdebug.StepFix
import com.diegonmarcos.superapp.adbdebug.StepState
import com.diegonmarcos.superapp.adbdebug.StepStatus
import com.diegonmarcos.superapp.configs.AdbCockpitModel
import com.diegonmarcos.superapp.configs.AdbCockpitOps
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** #1743 where every field of the previous Status page went (rule 16, held by test-c3-webserver-adb-status.sh and AdbStatusParityTest). */
object AdbStatusParity {
    /** The previous Status page: field -> the place it lives now. */
    val FIELD_HOME: Map<String, String> = mapOf(
        "Status card (ADB connected / ADB off line)" to "Status: connection card (the one verdict)",
        "Status card Connect" to "Status: Connect button",
        "Status card Re-check" to "Status: Re-check button",
        "Cockpit Connect / Disconnect toggle" to "Status: Connect / Disconnect buttons",
        "Cockpit Reconnect" to "Status: Reconnect button",
        "Cockpit master light and message" to "Status: connection card",
        "Cockpit Perms card" to "Status: step Perms",
        "Cockpit Setup card (Developer options, Wireless debugging)" to "Status: steps Developer options, Wireless debugging",
        "Cockpit Embedded card (Pairing, Connected)" to "Status: steps Paired, Embedded connect (full cards on All)",
        "Cockpit Local Server card" to "Status: steps Local server launched, Round trip (full card on All)",
        "Cockpit Shizuku card" to "Status: step Shizuku (full card on All)",
        "Cockpit mode selector" to "Server page: Profile (also on All)",
        "Cockpit keep-alive card" to "All",
        "Cockpit Logs card" to "All",
        "Tester (Run id -u)" to "All",
        "Logs (channel log)" to "All",
        "Home table rows" to "Home: ADB shell table (same AdbStatus verdict)",
    )
    /** The explicit owner removal: the STATUS / TESTER / LOGS panel stacked over the bottom of Status. */
    val REMOVED_FROM_STATUS = listOf("Tester", "Logs", "STATUS card")
}

/** The Status sub page: ONE connection card (the single verdict), its three buttons and the chain as a live step list. */
@Composable
internal fun AdbStatusPage() {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<ChannelFacts?>(null) }
    var readFailed by remember { mutableStateOf("") }
    var perms by remember { mutableStateOf<List<String>?>(null) }
    var clients by remember { mutableStateOf<List<String>?>(null) }
    var tick by remember { mutableStateOf(0) }
    var logTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val l: () -> Unit = { logTick++ }
        ChannelLog.shared.addListener(l); LocalServerAutoStart.addListener(l)
        onDispose { ChannelLog.shared.removeListener(l); LocalServerAutoStart.removeListener(l) }
    }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { runCatching { ChannelReader.read(ctx) } }
        facts = r.getOrNull(); readFailed = r.exceptionOrNull()?.let { "could not read the channel (${it.javaClass.simpleName})" } ?: ""
        perms = withContext(Dispatchers.IO) { runCatching { AdbCockpitOps.digest(ctx)?.missing }.getOrNull() }
        clients = withContext(Dispatchers.IO) { runCatching { ShellBroker.callerSummaries(ctx).map { it.first } }.getOrNull() }
    }
    fun run(label: String, work: () -> String) {
        if (busy.isNotEmpty()) return
        busy = label; said = ""
        scope.launch(Dispatchers.IO) {
            said = runCatching(work).getOrElse { "$label failed: ${it.message ?: it.javaClass.simpleName}" }
            busy = ""; tick++
        }
    }
    val live = remember(logTick) { runCatching { LocalServerAutoStart.lastSteps }.getOrDefault(emptyList()) }
    val f = facts?.copy(mode = ChannelModePrefs.current(ctx), connecting = busy == "Connect" || busy == "Reconnect" || facts?.connecting == true)
    val verdict = f?.let { AdbStatus.verdict(it) }
    val buttons = f?.let { AdbStatus.buttons(it) }
    val steps = f?.let {
        AdbStatus.steps(it, perms, clients,
            running = live.filter { s -> s.state == StepState.RUNNING }.map { s -> s.id }.toSet(),
            failed = live.filter { s -> s.state == StepState.FAILED }.associate { s -> s.id to s.detail.ifBlank { "failed" } })
    }.orEmpty()
    val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp).testTag("c3_adb_status"), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        DenseCard("Connection", "c3_adb_status_card") {
            if (readFailed.isNotEmpty()) DNote(readFailed)
            DRow("State", verdict?.let { it.title + ": " + it.detail } ?: "reading...",
                up = verdict?.let { when (it.dot) { com.diegonmarcos.superapp.adbdebug.Dot.OK -> true; com.diegonmarcos.superapp.adbdebug.Dot.BAD -> false; else -> null } }, tag = "c3_adb_status_state")
            Acts {
                fun en(b: StatusButton) = buttons?.get(b)
                Act("Connect", "c3_adb_status_connect", enabled = busy.isEmpty() && en(StatusButton.CONNECT)?.enabled == true) { run("Connect") { AdbCockpitOps.connect(ctx).note } }
                Act("Disconnect", "c3_adb_status_disconnect", enabled = busy.isEmpty() && en(StatusButton.DISCONNECT)?.enabled == true) { run("Disconnect") { ChannelOps.disconnectAll(ctx) } }
                Act("Reconnect", "c3_adb_status_reconnect", enabled = busy.isEmpty() && en(StatusButton.RECONNECT)?.enabled == true) { run("Reconnect") { ChannelOps.reconnectFull(ctx) } }
                Act("Re-check", "c3_adb_status_recheck", enabled = busy.isEmpty()) { tick++ }
            }
            // a disabled button always says why
            val why = buttons?.filter { !it.value.enabled }?.entries?.joinToString("  ·  ") { "${it.key.label}: ${it.value.why}" }
            if (!why.isNullOrBlank()) DNote(why)
            if (busy.isNotEmpty()) DNote("$busy...")
            if (said.isNotEmpty()) DNote(said)
        }
        DenseCard("Chain", "c3_adb_status_chain") {
            if (steps.isEmpty()) DNote("reading the device...")
            for (s in steps) StepRow(s, time) { fix ->
                run(fix.label.ifBlank { "Fix" }) { fixAction(ctx, fix, f) }
            }
            DNote("Every step is a live reading (a real round trip, never a cache). Tester and logs: ADB Shell > All.")
        }
    }
}

@Composable
private fun StepRow(s: StatusStep, time: SimpleDateFormat, fix: (StepFix) -> Unit) {
    val word = when (s.status) {
        StepStatus.OK -> "ok"; StepStatus.OK_CLOSED -> "ok-closed"; StepStatus.RUNNING -> "running"
        StepStatus.FAILED -> "failed"; StepStatus.SKIPPED -> "skipped"; StepStatus.UNKNOWN -> "unknown"
    }
    val up = when (s.status) { StepStatus.OK, StepStatus.OK_CLOSED -> true; StepStatus.FAILED -> false; else -> null }
    Row(Modifier.fillMaxWidth().testTag("c3_adb_step_${s.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { DRow(s.label, "$word · ${s.reason} · ${time.format(Date(s.at))}", up = up) }
        if (s.fix != StepFix.NONE && s.status != StepStatus.RUNNING) Act(s.fix.label, "c3_adb_step_${s.id}_fix") { fix(s.fix) }
    }
}

/** The one action each failed step offers; blocking, runs off the main thread. */
private fun fixAction(ctx: Context, fix: StepFix, f: ChannelFacts?): String = when (fix) {
    StepFix.NONE -> ""
    StepFix.PERMS, StepFix.CLIENTS -> AdbCockpitOps.run(ctx, AdbCockpitModel.Do.OPEN_PERMS, f?.adbPaired == true, f?.devOptions == true).note
    StepFix.ABOUT_PHONE -> ChannelOps.setup(ctx, SetupAction.OPEN_ABOUT_PHONE)
    StepFix.DEV_OPTIONS -> ChannelOps.setup(ctx, SetupAction.OPEN_DEVELOPER_OPTIONS)
    StepFix.WIRELESS_DEBUGGING -> ChannelOps.setup(ctx, SetupAction.OPEN_WIRELESS_DEBUGGING)
    StepFix.WIFI -> ChannelOps.setup(ctx, SetupAction.OPEN_WIFI)
    StepFix.PAIR -> ChannelOps.setup(ctx, SetupAction.PAIR)
    StepFix.SHIZUKU -> ChannelOps.setup(ctx, SetupAction.OPEN_SHIZUKU)
    StepFix.CONNECT -> AdbCockpitOps.connect(ctx).note
}
