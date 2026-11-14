package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.Api
import org.json.JSONObject

/**
 * The SSHd section (the web shell's renderSshd, /__app__/api/sshd, declared in build.json::ssh_doors): Keep alive with its live state,
 * every SSH door (state, engine, port and bind, observed bind, auth, mesh-only, authorized keys, host app, uptime, sessions, note) with
 * Start / Stop / Follow Keep alive and the per-port Mesh-only switch, the other SSH servers answering on loopback, and Copy All Infos.
 */
@Composable
fun SshdPage() {
    val (tick, bump) = rememberTick()
    val d = rememberObj(Api.SSHD, tick)
    val actions = rememberActions(bump)
    val ctx = LocalContext.current
    PageColumn("c3_sshd") {
        Acts { Act("Refresh", "c3_sshd_refresh") { bump() } }
        Loaded(d, "sshd") { data ->
            val on = data.optBoolean("keep_alive")
            val hs = data.objs("hosted").joinToString("; ") { h -> "sshd :${h.str("port")} " + if (h.optBoolean("listening")) "listening" else if (h.optBoolean("wanted")) "not listening yet" else "off" }
            Card("Keep alive", "c3_keepalive", badges = { Badge(if (on) "ON" else "OFF", if (on) Tone.Up else Tone.Down) }) {
                SwitchRow("Keep alive", on, "Keep alive is ${if (on) "ON" else "OFF"}; the supervised door is ${if (data.optBoolean("wanted")) "wanted" else "not wanted"}${if (hs.isNotEmpty()) "; $hs" else ""}. ON keeps the server and the hosted sshd up with a notification and starts them at boot; OFF stops them when the app closes.", "c3_sshd_keep_alive") {
                    actions.post(Api.POWER, JSONObject().put("keep_alive", it)) { "Applied" }
                }
            }
            val cidrs = data.optJSONArray("mesh_cidrs").strs()
            DenseCard("SSH servers · ${data.objs("doors").size}", "c3_sshd_servers", trailing = { DLink("Re-check all", "c3_sshd_recheck_all") { bump() } }) {
                data.objs("doors").forEach { door -> Door(door, cidrs, actions, bump) }
            }
            val other = data.objs("other")
            if (other.isNotEmpty()) Card("Other SSH servers answering on loopback", "c3_sshd_other") { other.forEach { x -> Kv("tcp ${x.str("port")}", "${x.str("engine")} (${x.str("banner")})") } }
            Card("All doors", "c3_sshd_all") {
                Acts { Act("Copy All Infos", "c3_copy_doors") { copyText(ctx, "doors", doorsText(data)) } }
                Note("Keep alive ${if (on) "ON" else "OFF"}; the supervised door is ${if (data.optBoolean("wanted")) "wanted" else "not wanted"}")
            }
        }
    }
}

/** doorFacts: the rows of one door. */
fun doorFacts(d: JSONObject): List<Pair<String, String>> {
    val ses = if (!d.has("sessions") || d.isNull("sessions")) "unknown: ${d.str("sessions_note")}"
        else d.optJSONArray("sessions").strs().let { if (it.isEmpty()) "none connected" else "${it.size} connected: ${it.joinToString(", ")}" }
    val port = if (!d.has("port") || d.isNull("port")) "no port" else "tcp ${d.str("port")}"
    return listOf(
        "State" to d.str("state"),
        "Engine" to d.str("engine") + (d.optString("banner").takeIf { it.isNotEmpty() && it != "null" }?.let { " ($it)" } ?: ""),
        "Port and bind" to port + (d.optString("bind").takeIf { it.isNotEmpty() && it != "null" }?.let { " on $it" } ?: ""),
        "Bind (observed)" to bindText(d),
        "Auth" to d.str("auth"),
        "Mesh-only (this port)" to if (!d.has("mesh_only")) "-" else if (d.optBoolean("mesh_only")) "ON" else "OFF",
        "Authorized keys" to if (!d.has("authorized_keys") || d.isNull("authorized_keys")) "not readable here: ${d.str("authorized_keys_note")}" else d.str("authorized_keys"),
        "Hosted by" to d.str("app") + (d.optString("package").takeIf { it.isNotEmpty() && it != "null" }?.let { " ($it)" } ?: ""),
        "Seen up" to fmtUp(d.longOrNull("seen_up_s")),
        "Sessions" to ses,
        "Note" to d.str("note"),
    )
}

fun bindText(d: JSONObject): String = when (val v = d.optString("bind_state")) {
    "n/a" -> "no listener"
    "unknown" -> "not observable: this Android hides /proc/net from apps"
    else -> d.optJSONArray("binds").strs().let { if (it.isEmpty()) "nothing listening" else it.joinToString(", ") } + " (" + (if (v == "exposed") "all interfaces / EXPOSED" else v) + ")"
}

fun doorsText(data: JSONObject): String {
    val out = mutableListOf("Keep alive: ${if (data.optBoolean("keep_alive")) "ON" else "OFF"} (mesh ranges ${data.optJSONArray("mesh_cidrs").strs().joinToString(", ")})")
    data.objs("doors").forEach { d -> out += d.str("title") + "\n" + sshExpanded(d).joinToString("\n") { "  ${it.first}: ${it.second}" } }
    data.objs("other").forEach { x -> out += "Other SSH server\n  tcp ${x.str("port")}: ${x.str("engine")} (${x.str("banner")})" }
    return out.joinToString("\n\n")
}

/** The dot of the real verdict: green / amber / red from the kit's state tokens, grey (the dim ink) while not checked. */
@Composable
fun StateDot(s: SshState) {
    val c = when (s) { SshState.GREEN -> Tone.Up; SshState.AMBER -> Tone.Warn; SshState.RED -> Tone.Bad; SshState.GREY -> Tone.Down }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(8.dp).background(c, androidx.compose.foundation.shape.CircleShape))
}

/** One server: a compact row (dot, name, status word, port, mesh yes/no, uptime) that expands to the checks, facts and actions. */
@Composable
private fun Door(d: JSONObject, cidrs: List<String>, actions: Actions, bump: () -> Unit) {
    val ctx = LocalContext.current
    val id = d.optString("id")
    var open by remember(id) { mutableStateOf(false) }
    val row = sshRow(d)
    val widths = listOf(null, 88, 52, 52, 60)
    androidx.compose.foundation.layout.Column(Modifier.testTag("c3_door_$id")) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }.padding(horizontal = 6.dp, vertical = 3.dp).testTag("c3_door_row_$id"),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            StateDot(sshState(d))
            androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
            row.forEachIndexed { i, c ->
                val w = widths[i]
                Text(c, style = MaterialTheme.typography.bodySmall, color = if (i == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    modifier = if (w == null) Modifier.weight(1f) else Modifier.width(w.dp).padding(end = 3.dp))
            }
            Text(if (open) "v" else ">", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                val st = d.str("state")
                // the server's state word is now itself derived from the real verdict; a live word is only drawn up while the verdict is green
                Badge(st, if ((st == "listening" || st == "connected") && sshState(d) == SshState.GREEN) Tone.Up else Tone.Down)
                when (d.optString("bind_state")) { "mesh-only" -> Badge("mesh-only", Tone.Up); "exposed" -> Badge("all interfaces / EXPOSED", Tone.Bad); else -> {} }
            }
            sshExpanded(d).forEach { (k, v) -> Kv(k, v, mono = k == "Host key" || k == "ssh command") }
            val c = d.optJSONObject("control") ?: JSONObject()
            val avail = c.optBoolean("available")
            fun op(o: String) { if (!avail) { toast(ctx, c.optString("note")) } else { actions.post(Api.SSHD, JSONObject().put("door", id).put("op", o)) { "Asked" } } }
            Acts {
                Act("Start", "c3_door_start") { op("start") }
                Act("Stop", "c3_door_stop") { op("stop") }
                Act("Restart", "c3_door_restart") { op("restart") }
                Act("Re-check", "c3_door_recheck") { bump() }
                Act("Copy ssh command", "c3_door_copy_ssh") { copyText(ctx, "ssh command", sshCommand(d)) }
                if (avail && c.has("explicit") && !c.isNull("explicit")) Act("Follow Keep alive", "c3_door_follow") { op("follow") }
            }
            Note(c.optString("note"))
            if (d.has("port") && !d.isNull("port")) {
                val on = d.optBoolean("mesh_only")
                SwitchRow("SSH reachable over mesh VPN only", on, "Port ${d.str("port")} only. ON restricts it to loopback and the mesh ranges ${cidrs.joinToString(", ")} (resolved at each start; with no mesh address it stays closed). OFF opens every interface, Wi-Fi, LAN and cellular included.", "c3_door_mesh_only") {
                    actions.post(Api.SSHD, JSONObject().put("door", id).put("mesh_only", it)) { "Mesh-only applied" }
                }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.Line))
    }
}
