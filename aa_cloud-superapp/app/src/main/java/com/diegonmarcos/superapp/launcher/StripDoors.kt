package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.network.LocalDoors
import com.diegonmarcos.superapp.network.UsbDataModel

/** #1684 the WebS / SSHd / TypC indicators' state -> tint mapping, pure so a JVM test pins on / off / unknown. */
internal object StripDoors {
    /** Not checked yet, or the build declared no port: neither green nor "down". */
    const val UNKNOWN_TINT: Int = 0x55FFFFFF
    /** A cable that charges but moves no data. */
    const val CHARGE_TINT: Int = 0xFFFBBF24.toInt()

    fun websState(p: LocalDoors.Probe?): LocalDoors.State = p?.state ?: LocalDoors.State.UNKNOWN
    fun sshdState(ps: List<LocalDoors.Probe>): LocalDoors.State = LocalDoors.combine(ps.map { it.state })

    fun tint(s: LocalDoors.State, on: Int, off: Int): Int = when (s) {
        LocalDoors.State.ON -> on; LocalDoors.State.OFF -> off; LocalDoors.State.UNKNOWN -> UNKNOWN_TINT
    }

    /** The one dot under WebS / SSHd: filled = up, outline = down, faint outline = not checked (ADB's dot style, #1721). */
    fun dot(s: LocalDoors.State): Int = AdbPeerDots.tint(when (s) { LocalDoors.State.ON -> true; LocalDoors.State.OFF -> false; LocalDoors.State.UNKNOWN -> null })

    fun describe(s: LocalDoors.State, ps: List<LocalDoors.Probe>): String =
        s.name.lowercase() + ps.joinToString("") { "; ${it.door.title} :${it.door.port ?: "?"} ${it.detail}" }

    /** #1746 SSHd: green = on, amber (the charge-only amber) = partial, grey = off, dim = not checked. */
    fun tintV(v: SshdVerdict.V, on: Int, off: Int): Int = when (v) {
        SshdVerdict.V.GREEN -> on; SshdVerdict.V.AMBER -> CHARGE_TINT; SshdVerdict.V.RED -> off; SshdVerdict.V.GREY -> UNKNOWN_TINT
    }
    fun dotV(v: SshdVerdict.V): Int = when (v) {
        SshdVerdict.V.GREEN -> AdbPeerDots.tint(true); SshdVerdict.V.AMBER -> CHARGE_TINT; SshdVerdict.V.RED -> AdbPeerDots.tint(false); SshdVerdict.V.GREY -> AdbPeerDots.tint(null)
    }
    fun sshdVerdict(loops: List<LocalDoors.Probe>, mesh: List<LocalDoors.Probe?>, meshChecked: Boolean): SshdVerdict.V =
        SshdVerdict.combine(loops.mapIndexed { i, p -> SshdVerdict.ofDoor(p, mesh.getOrNull(i), meshChecked) })
    fun describeSshd(v: SshdVerdict.V, loops: List<LocalDoors.Probe>, mesh: List<LocalDoors.Probe?>, meshChecked: Boolean): String =
        SshdVerdict.word(v, emptyList()).substringBefore(" (") + loops.mapIndexed { i, p ->
            val (dv, failed) = SshdVerdict.verdict(SshdVerdict.checks(p, mesh.getOrNull(i), meshChecked))
            "; ${p.door.title} :${p.door.port ?: "?"} ${SshdVerdict.word(dv, failed)} (${p.detail}${if (meshChecked) "; mesh " + (mesh.getOrNull(i)?.detail ?: "no mesh address") else ""})"
        }.joinToString("")

    fun cableTint(c: UsbDataModel.Cable, on: Int, off: Int): Int = when (c) {
        UsbDataModel.Cable.DATA -> on; UsbDataModel.Cable.CHARGE -> CHARGE_TINT; UsbDataModel.Cable.NONE -> off
    }
}
