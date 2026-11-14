#!/usr/bin/env bash
# #1743 the ADB Shell Status page: ONE verdict (libs:shizuku-adb-debug-tools AdbStatus), the chain as a step list, buttons by state, and the
# explicit removal of the bottom STATUS / TESTER / LOGS overlay (Tester and Logs live on the All page only). Parity (rule 16): every field of the
# previous Status page is listed in AdbStatusParity.FIELD_HOME with where it went. Every check is mutated and must turn red. Static.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
python3 - "$APP" "$ROOT" <<'PY'
import os, re, sys
APP, ROOT = sys.argv[1], sys.argv[2]
def rd(p):
    try: return open(p, encoding="utf-8").read()
    except OSError: return ""
OWN = os.path.join(APP, "src-tauri/gen/android/app/src/owner/java/com/diegonmarcos/cloudwebserver/owner/")
LIB = os.path.join(ROOT, "ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/")
STEPS = ["perms", "dev-options", "wireless-debugging", "paired", "embedded-connect", "local-server-launched", "round-trip", "profile", "clients"]
ROWS = ["Perms", "Developer options", "Wireless debugging", "Paired", "Embedded connect", "Local server launched", "Round trip", "Profile", "Clients using it"]
OVERLAY = ['"Run id -u"', "ShellBroker.exec", "ChannelLog.shared.asText", "c3_adb_owner_tester", "c3_adb_owner_logs", "AdbStatusTab()", "AdbReadiness.connect"]
HOMES = ["Status card (ADB connected / ADB off line)", "Status card Connect", "Status card Re-check", "Cockpit Connect / Disconnect toggle", "Cockpit Reconnect",
  "Cockpit master light and message", "Cockpit Perms card", "Cockpit Setup card (Developer options, Wireless debugging)", "Cockpit Embedded card (Pairing, Connected)",
  "Cockpit Local Server card", "Cockpit Shizuku card", "Cockpit mode selector", "Cockpit keep-alive card", "Cockpit Logs card", "Tester (Run id -u)", "Logs (channel log)", "Home table rows"]

def check(page, side, lib, home, server):
    bad = []
    for s in STEPS:
        if '"%s"' % s not in lib: bad.append("L1 AdbStatus lacks step id %s" % s)
    for r in ROWS:
        if '"%s"' % r not in lib: bad.append("L2 AdbStatus lacks the row label %s" % r)
    for n in ("fun verdict(", "fun buttons(", "fun steps(", "OK_CLOSED", "StatusButton.CONNECT", "StatusButton.DISCONNECT", "StatusButton.RECONNECT"):
        if n not in lib: bad.append("L3 AdbStatus lacks %s" % n)
    if "ChannelState.connState(" not in lib: bad.append("L4 the verdict is not ChannelState.connState")
    for n in ("AdbStatus.verdict(", "AdbStatus.buttons(", "AdbStatus.steps(", "ChannelOps.disconnectAll(", "ChannelOps.reconnectFull(", "AdbCockpitOps.connect(", "StepRow(",
              '"c3_adb_status_connect"', '"c3_adb_status_disconnect"', '"c3_adb_status_reconnect"', '"c3_adb_status_recheck"', "en(StatusButton.DISCONNECT)", "why"):
        if n not in page: bad.append("S1 the Status page lacks %s" % n)
    for n in OVERLAY:
        if n in page: bad.append("S2 the removed overlay field %s is back on Status" % n)
    if "else -> AdbStatusPage()" not in side: bad.append("S3 the owner side does not draw AdbStatusPage on Status")
    if "fun StatusTab(" in side: bad.append("S4 the old Status tab (bottom overlay) is still in AdbOwnerSide")
    if "TesterAndLogs()" not in side or "AdbReadiness.connect(" not in side or '"Run id -u"' not in side: bad.append("S5 Tester / Logs / readiness are not on the All page")
    if "REMOVED_FROM_STATUS" not in page: bad.append("P1 the removal is not recorded in AdbStatusParity.REMOVED_FROM_STATUS")
    for h in HOMES:
        if '"%s" to' % h not in page: bad.append("P2 FIELD_HOME lacks %r" % h)
    if "AdbStatus.verdict(" not in home: bad.append("C1 Home table does not read the one verdict")
    if "AdbStatus.verdict(" not in server: bad.append("C2 Server page does not read the one verdict")
    for f, n in (("page", page), ("home", home), ("server", server)):
        if re.search(r"ChannelState\.connState\(|degradedWhy\(|ConnState\.DEGRADED", n): bad.append("C3 %s derives its own verdict" % f)
    return bad

page = rd(OWN + "AdbStatusPage.kt"); side = rd(OWN + "AdbOwnerSide.kt"); lib = rd(LIB + "AdbStatus.kt"); home = rd(OWN + "AdbHomeStatus.kt"); server = rd(OWN + "AdbServerTab.kt")
bad = check(page, side, lib, home, server); fails = 0
print(("  PASS: " if not bad else "  FAIL: ") + "Status = one verdict + step list + buttons by state; overlay removed; parity recorded" + ("" if not bad else "\n    " + "\n    ".join(bad)))
fails += 1 if bad else 0
def mut(name, **kw):
    global fails
    a = dict(page=page, side=side, lib=lib, home=home, server=server); a.update(kw)
    red = bool(check(**a)); print(("  PASS: " if red else "  FAIL: ") + "mutation turns red: " + name); fails += 0 if red else 1
for s in STEPS: mut("step %s dropped" % s, lib=lib.replace('"%s"' % s, '"x"'))
for n in OVERLAY: mut("overlay %s back on Status" % n, page=page + "\n" + n)
for h in HOMES: mut("FIELD_HOME drops %s" % h, page=page.replace('"%s" to' % h, '"x" to'))
mut("old tab back", side=side + "\nprivate fun StatusTab(p: Int) {}")
mut("tester off All", side=side.replace("TesterAndLogs()", "X"))
mut("home own verdict", home=home + "\nChannelState.connState(f)")
mut("server own verdict", server=server + "\nConnState.DEGRADED")
mut("button dropped", page=page.replace('"c3_adb_status_reconnect"', '"x"'))
sys.exit(1 if fails else 0)
PY
