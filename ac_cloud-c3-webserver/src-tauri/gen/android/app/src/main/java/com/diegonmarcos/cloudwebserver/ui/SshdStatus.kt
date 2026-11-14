package com.diegonmarcos.cloudwebserver.ui

import org.json.JSONObject

/**
 * #1746 the SSHd page's REAL status, as the server reports it (server/src/sshd_status.rs): the verdict is computed there from live
 * checks (process alive, port listening, an `SSH-2.0-` banner read back on loopback, Wi-Fi LAN and the wg0 mesh address), never from
 * a flag. This file only turns that JSON into the words and rows the page draws, pure so a JVM test pins every row.
 * green = all required checks passed; amber = partial (the failed ones are named); red = off; grey = not checked yet.
 */
enum class SshState { GREEN, AMBER, RED, GREY }

fun sshState(d: JSONObject): SshState = when (d.optJSONObject("status")?.optString("verdict")) {
    "green" -> SshState.GREEN
    "amber" -> SshState.AMBER
    "red" -> SshState.RED
    else -> SshState.GREY
}

/** The word beside the dot: UP / PARTIAL (failed checks) / OFF / NOT CHECKED. */
fun sshWord(d: JSONObject): String {
    val st = d.optJSONObject("status") ?: return if (d.has("port") && !d.isNull("port")) "NOT CHECKED" else "NO PORT"
    val label = st.optString("label").ifEmpty { "NOT CHECKED" }
    val failed = st.optJSONArray("failed").strs()
    return if (sshState(d) == SshState.AMBER && failed.isNotEmpty()) "$label: ${failed.joinToString(", ")} failed" else label
}

fun sshMesh(d: JSONObject): String {
    val st = d.optJSONObject("status") ?: return "-"
    return if (!st.has("mesh_reachable") || st.isNull("mesh_reachable")) "-" else if (st.optBoolean("mesh_reachable")) "yes" else "no"
}

/** One compact row: name, status word, port, mesh reachable, uptime. */
fun sshRow(d: JSONObject): List<String> = listOf(
    d.str("title"), sshWord(d),
    if (d.has("port") && !d.isNull("port")) ":${d.str("port")}" else "no port",
    "mesh " + sshMesh(d),
    "up " + fmtUp(d.longOrNull("seen_up_s")),
)

private fun clock(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

private val CHECK_LABEL = mapOf("process" to "Process alive", "port" to "Port listening", "loopback" to "Banner on loopback", "lan" to "Banner on Wi-Fi LAN", "mesh" to "Banner on mesh (wg0)")

/** Every check with its result, detail, round trip and the time it ran. */
fun sshChecks(d: JSONObject): List<Pair<String, String>> {
    val arr = d.optJSONObject("status")?.optJSONArray("checks").objs()
    if (arr.isEmpty()) return listOf("Checks" to "not run: ${if (d.has("port") && !d.isNull("port")) "no answer from the server" else "this app has no sshd"}")
    return arr.map { c ->
        val ok = if (!c.has("ok") || c.isNull("ok")) null else c.optBoolean("ok")
        val res = when { ok == true -> "ok"; ok == false -> "FAIL"; else -> "not checked" } + (if (!c.optBoolean("required")) " (not required)" else "")
        val rtt = if (c.has("rtt_ms") && !c.isNull("rtt_ms")) " · ${c.optLong("rtt_ms")} ms" else ""
        val at = if (c.has("at_ms") && !c.isNull("at_ms") && c.optLong("at_ms") > 0) " · ${clock(c.optLong("at_ms"))}" else ""
        (CHECK_LABEL[c.optString("id")] ?: c.optString("id")) to "$res · ${c.optString("detail")}$rtt$at"
    }
}

fun sshCommand(d: JSONObject): String = d.optJSONObject("status")?.optString("ssh_command").orEmpty().ifEmpty { "ssh -p ${d.str("port")} ${d.str("user", "user")}@<mesh-ip>" }

/** The expanded content: the previous door rows (every one kept) plus the real checks, process, user, addresses, key, auth, keys, log, config. */
fun sshExpanded(d: JSONObject): List<Pair<String, String>> {
    val st = d.optJSONObject("status")
    val log = d.optJSONArray("log_tail").objs().map { "${clock(it.optLong("at_ms"))} ${it.optString("event")}" }
    val pid = if (d.has("pid") && !d.isNull("pid")) d.str("pid") else "none: ${d.str("pid_note")}"
    val listen = st?.let { "loopback 127.0.0.1" + (it.optString("lan_ip").takeIf { s -> s.isNotEmpty() && s != "null" }?.let { s -> ", Wi-Fi $s" } ?: "") + (it.optString("mesh_ip").takeIf { s -> s.isNotEmpty() && s != "null" }?.let { s -> ", mesh $s" } ?: ", mesh: no address") } ?: "-"
    return listOf("Status" to (sshWord(d) + (st?.optString("reason")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))) +
        sshChecks(d) +
        listOf(
            "Process / pid" to pid,
            "User" to d.str("user"),
            "Server addresses" to listen,
            "Host key" to (if (d.has("host_key") && !d.isNull("host_key")) d.str("host_key") else "not readable: ${d.str("host_key_note")}"),
            "Auth methods" to d.optJSONArray("auth_methods").strs().joinToString(", ").ifEmpty { d.str("auth") },
            "Authorized keys" to (if (d.has("keys_count") && !d.isNull("keys_count")) d.str("keys_count") else "not readable here: ${d.str("authorized_keys_note")}"),
            "Last connections" to (if (log.isEmpty()) (d.optString("log_note").ifEmpty { "none yet" }) else log.joinToString("\n")),
            "Config path" to d.str("config_path"),
        ) + doorFacts(d) + listOf("ssh command" to sshCommand(d))
}
