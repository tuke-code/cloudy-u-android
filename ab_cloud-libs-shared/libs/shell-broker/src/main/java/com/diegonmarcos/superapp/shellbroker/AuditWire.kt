package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.ShellWire
import org.json.JSONObject

/** #1748 The owner-side encoders of the audit / caller-summary answers (moved out of libs:shell-client, which only decodes them: it is a minimal contract lib). */
object AuditWire {
    fun encodeAudit(rows: List<ShellWire.AuditRow>): String = org.json.JSONArray().apply {
        rows.forEach { r -> put(JSONObject().put("ts", r.ts).put("caller", r.caller).put("cmd", r.cmd).put("outcome", r.outcome)
            .put("exit", r.exit).put("ms", r.ms).put("source", r.source)) }
    }.toString()

    fun encodeCallers(rows: List<Pair<String, String>>): String =
        org.json.JSONArray().apply { rows.forEach { (k, v) -> put(JSONObject().put("caller", k).put("summary", v)) } }.toString()
}
