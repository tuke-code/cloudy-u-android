package com.diegonmarcos.cloudwebserver

import com.diegonmarcos.cloudwebserver.ui.SshState
import com.diegonmarcos.cloudwebserver.ui.doorFacts
import com.diegonmarcos.cloudwebserver.ui.sshChecks
import com.diegonmarcos.cloudwebserver.ui.sshCommand
import com.diegonmarcos.cloudwebserver.ui.sshExpanded
import com.diegonmarcos.cloudwebserver.ui.sshMesh
import com.diegonmarcos.cloudwebserver.ui.sshRow
import com.diegonmarcos.cloudwebserver.ui.sshState
import com.diegonmarcos.cloudwebserver.ui.sshWord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1746 the SSHd page's rows and expanded content, over the JSON the server's /__app__/api/sshd sends. */
class SshdStatusTest {
    private fun door(verdict: String?, label: String = "UP", failed: String = "[]", mesh: String = "true", extra: String = "") = JSONObject(
        """{"id":"webserver","title":"Cloud C3 Webserver","app":"ac_cloud-c3-webserver","package":"p","engine":"sshd","banner":"SSH-2.0-russh_0.65","port":8025,"bind":"x","bind_state":"mesh-only","state":"listening",
        "auth":"pubkey","mesh_only":true,"authorized_keys":3,"authorized_keys_note":"","seen_up_s":125,"sessions":[],"sessions_note":"","note":"n","user":"webserver","pid":4242,"pid_note":"own",
        "host_key":"SHA256:abc","host_key_note":"","auth_methods":["publickey"],"keys_count":3,"config_path":"/data/sshd_host_ed25519","log_note":"",
        "log_tail":[{"at_ms":1000,"event":"accepted 10.0.0.2"}],
        ${if (verdict == null) "" else """"status":{"verdict":"$verdict","label":"$label","failed":$failed,"reason":"r","mesh_reachable":$mesh,"mesh_ip":"10.0.0.7","lan_ip":"192.168.1.20",
        "ssh_command":"ssh -p 8025 webserver@10.0.0.7","checks":[{"id":"process","required":true,"ok":true,"detail":"alive","at_ms":1000},
        {"id":"port","required":true,"ok":true,"detail":"accepts","rtt_ms":1,"at_ms":1000},{"id":"loopback","required":true,"ok":true,"detail":"127.0.0.1: SSH-2.0-russh","rtt_ms":2,"at_ms":1000},
        {"id":"lan","required":false,"ok":null,"detail":"no Wi-Fi / LAN address","at_ms":1000},{"id":"mesh","required":true,"ok":$mesh,"detail":"10.0.0.7: x","rtt_ms":3,"at_ms":1000}]},"""}
        "control":{"available":true}$extra}""")

    @Test fun `the dot and the word follow the verdict, never the old state`() {
        assertEquals(SshState.GREEN, sshState(door("green")))
        assertEquals(SshState.AMBER, sshState(door("amber", "PARTIAL", """["mesh"]""", "false")))
        assertEquals(SshState.RED, sshState(door("red", "OFF", """["port"]""", "false")))
        assertEquals(SshState.GREY, sshState(door("grey", "NOT CHECKED")))
        // "state":"listening" is in the JSON but a door with no status is NOT green
        assertEquals(SshState.GREY, sshState(door(null)))
        assertEquals("NOT CHECKED", sshWord(door(null)))
        assertEquals("OFF", sshWord(door("red", "OFF")))
        assertEquals("PARTIAL: mesh failed", sshWord(door("amber", "PARTIAL", """["mesh"]""", "false")))
        assertEquals("NO PORT", sshWord(JSONObject("""{"title":"x"}""")))
    }

    @Test fun `the compact row is name, word, port, mesh yes or no, uptime`() {
        assertEquals(listOf("Cloud C3 Webserver", "UP", ":8025", "mesh yes", "up 2 min 5 s"), sshRow(door("green")))
        assertEquals("mesh no", sshRow(door("amber", "PARTIAL", """["mesh"]""", "false"))[3])
        assertEquals("mesh -", sshRow(door(null))[3])
    }

    @Test fun `every check is listed with its result and time`() {
        val c = sshChecks(door("green")).toMap()
        assertTrue(c.getValue("Process alive").startsWith("ok"))
        assertTrue(c.getValue("Banner on mesh (wg0)").startsWith("ok"))
        assertTrue(c.getValue("Banner on Wi-Fi LAN").startsWith("not checked (not required)"))
        assertTrue(c.getValue("Banner on loopback").contains("2 ms"))
        assertTrue(sshChecks(door("amber", "PARTIAL", """["mesh"]""", "false")).toMap().getValue("Banner on mesh (wg0)").startsWith("FAIL"))
        assertTrue(sshChecks(door(null)).single().second.startsWith("not run"))
    }

    @Test fun `the expanded content has the real checks, the new facts, and every previous door row`() {
        val d = door("green")
        val keys = sshExpanded(d).map { it.first }
        for (k in listOf("Status", "Process alive", "Process / pid", "User", "Server addresses", "Host key", "Auth methods", "Authorized keys", "Last connections", "Config path", "ssh command")) assertTrue(k, k in keys)
        // rule 16: parity with the previous page: each row of doorFacts is still drawn, same value
        val now = sshExpanded(d).toMap()
        for ((k, v) in doorFacts(d)) assertEquals(k, v, now[k] ?: error("lost row $k"))
        assertEquals("ssh -p 8025 webserver@10.0.0.7", sshCommand(d))
        assertTrue(now.getValue("Server addresses").contains("mesh 10.0.0.7"))
        assertTrue(now.getValue("Last connections").contains("accepted 10.0.0.2"))
        assertEquals("yes", sshMesh(d))
    }

    @Test fun `a server without status or with unreadable facts still draws, with the reason`() {
        val d = JSONObject("""{"id":"nix","title":"Cloud Terminal Nix","port":8024,"state":"down","engine":"OpenSSH","auth":"pubkey","control":{"available":false},"authorized_keys_note":"private","host_key_note":"other uid"}""")
        val now = sshExpanded(d).toMap()
        assertTrue(now.getValue("Host key").startsWith("not readable"))
        assertTrue(now.getValue("Authorized keys").startsWith("not readable"))
        assertTrue(sshCommand(d).startsWith("ssh -p 8024"))
    }
}
