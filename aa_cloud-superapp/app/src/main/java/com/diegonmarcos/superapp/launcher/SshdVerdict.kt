package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.network.LocalDoors

/**
 * #1746 the strip's SSHd verdict, the SAME rule as the Cloud C3 Webserver's SSHd page (ac_cloud-c3-webserver/server/src/sshd_status.rs,
 * both pinned to data/sshd-verdict-vectors.json): green only when every required check passed, amber when some passed and some failed
 * (which ones is named), red when none did (off), grey when nothing was checked yet. The strip's checks are the ones it can run from
 * here: the port (TCP connect) and the `SSH-2.0-` banner on loopback, and the banner on the WireGuard mesh address (wg0). Never a flag.
 */
internal object SshdVerdict {
    enum class V { GREEN, AMBER, RED, GREY }
    data class Check(val id: String, val required: Boolean, val ok: Boolean?)

    fun verdict(checks: List<Check>): Pair<V, List<String>> {
        val req = checks.filter { it.required }
        val failed = req.filter { it.ok == false }.map { it.id }
        val passed = req.count { it.ok == true }
        val v = when {
            req.isEmpty() || req.all { it.ok == null } -> V.GREY
            failed.isEmpty() -> if (passed == req.size) V.GREEN else V.GREY
            passed == 0 -> V.RED
            else -> V.AMBER
        }
        return v to failed
    }

    /** One door from its loopback round trip and its mesh-address round trip (null mesh probe = no mesh address on this phone). */
    fun checks(loop: LocalDoors.Probe?, mesh: LocalDoors.Probe?, meshChecked: Boolean): List<Check> {
        fun ok(p: LocalDoors.Probe?): Boolean? = when (p?.state) { LocalDoors.State.ON -> true; LocalDoors.State.OFF -> false; else -> null }
        // The port check passes when something accepted the connection: a probe that read no banner still connected.
        val connected = loop?.let { it.state == LocalDoors.State.ON || it.detail.startsWith("connected") }
        return listOf(
            Check("port", true, connected),
            Check("loopback", true, ok(loop)),
            Check("mesh", true, if (!meshChecked) null else if (mesh == null) false else ok(mesh)),
        )
    }

    fun ofDoor(loop: LocalDoors.Probe?, mesh: LocalDoors.Probe?, meshChecked: Boolean): V = verdict(checks(loop, mesh, meshChecked)).first

    /** Several doors on one chip: the best door wins (green > amber > red); grey only when none was checked. */
    fun combine(vs: List<V>): V = when {
        vs.any { it == V.GREEN } -> V.GREEN
        vs.any { it == V.AMBER } -> V.AMBER
        vs.isNotEmpty() && vs.all { it == V.RED } -> V.RED
        else -> V.GREY
    }

    /** "partial: mesh failed" style words for the chip's description. */
    fun word(v: V, failed: List<String>): String = when (v) {
        V.GREEN -> "up"; V.AMBER -> "partial (" + failed.joinToString(", ") + " failed)"; V.RED -> "off"; V.GREY -> "not checked"
    }
}
