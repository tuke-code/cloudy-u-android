//! #1746 the REAL state of an sshd, one source for every place that shows it (the SSHd page, the Home table, the Store, the
//! SuperApp strip). Never a flag, a preference or "a port accepted a TCP connection": a server is judged by checks that each did a
//! real round trip, and the verdict is a pure function of those checks ([`verdict`], pinned by data/sshd-verdict-vectors.json,
//! which the SuperApp's SshdVerdict.kt is pinned to as well).
//!
//! Checks per server: `process` (the hosted engine is alive; other apps' processes cannot be seen from this uid, so it is not
//! required there), `port` (a TCP connect on loopback), then one `loopback` / `lan` / `mesh` check each: connect to that
//! interface address and read the `SSH-2.0-` identification line back. `lan` is required only when Mesh-only is OFF and a Wi-Fi
//! address exists; `mesh` is always required (reachable from the mesh means the banner answers on the wg0 address).

use serde_json::{json, Value};
use std::io::Read;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::{Duration, Instant};

#[derive(Clone, Debug, PartialEq)]
pub struct Check {
    pub id: String,
    pub required: bool,
    /// None = not checked yet.
    pub ok: Option<bool>,
    pub detail: String,
    pub rtt_ms: Option<u64>,
    /// Unix ms when it ran.
    pub at_ms: u64,
}

impl Check {
    pub fn new(id: &str, required: bool, ok: Option<bool>, detail: &str) -> Check {
        Check { id: id.to_string(), required, ok, detail: detail.to_string(), rtt_ms: None, at_ms: now_ms() }
    }
    pub fn to_json(&self) -> Value {
        json!({"id": self.id, "required": self.required, "ok": self.ok, "detail": self.detail, "rtt_ms": self.rtt_ms, "at_ms": self.at_ms})
    }
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Verdict {
    Green,
    Amber,
    Red,
    Grey,
}

impl Verdict {
    pub fn word(self) -> &'static str {
        match self {
            Verdict::Green => "green",
            Verdict::Amber => "amber",
            Verdict::Red => "red",
            Verdict::Grey => "grey",
        }
    }
    /// The word shown beside the dot.
    pub fn label(self) -> &'static str {
        match self {
            Verdict::Green => "UP",
            Verdict::Amber => "PARTIAL",
            Verdict::Red => "OFF",
            Verdict::Grey => "NOT CHECKED",
        }
    }
}

/// The verdict and the ids of the required checks that failed. Only required checks count:
/// green = all passed; red = none passed; amber = some passed and some failed; grey = no required check has run, or a required one
/// is still unchecked and none failed.
pub fn verdict(checks: &[Check]) -> (Verdict, Vec<String>) {
    let req: Vec<&Check> = checks.iter().filter(|c| c.required).collect();
    let failed: Vec<String> = req.iter().filter(|c| c.ok == Some(false)).map(|c| c.id.clone()).collect();
    let passed = req.iter().filter(|c| c.ok == Some(true)).count();
    let v = if req.is_empty() || req.iter().all(|c| c.ok.is_none()) {
        Verdict::Grey
    } else if failed.is_empty() {
        if passed == req.len() { Verdict::Green } else { Verdict::Grey }
    } else if passed == 0 {
        Verdict::Red
    } else {
        Verdict::Amber
    };
    (v, failed)
}

/// "SSH-2.0-OpenSSH_9.8" out of the bytes a server sent first. RFC 4253 4.2 lets a server send other lines before the
/// identification, so a few are skipped; only protocol 2.0 / 1.99 counts and the software field must be present.
pub fn parse_ident(bytes: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(bytes);
    for line in text.split('\n').take(5) {
        let line = line.trim_end_matches('\r').trim();
        if line.starts_with("SSH-") {
            let rest = line.strip_prefix("SSH-2.0-").or_else(|| line.strip_prefix("SSH-1.99-"))?;
            return if rest.is_empty() { None } else { Some(line.chars().take(120).collect()) };
        }
    }
    None
}

/// One real handshake step: connect to `ip:port` and read the identification line.
pub fn probe_addr(id: &str, required: bool, ip: IpAddr, port: u16, timeout: Duration) -> Check {
    let t0 = Instant::now();
    let addr = SocketAddr::new(ip, port);
    let mut c = Check::new(id, required, Some(false), "");
    match TcpStream::connect_timeout(&addr, timeout) {
        Err(e) => c.detail = format!("{}: {}", ip, if e.kind() == std::io::ErrorKind::ConnectionRefused { "refused (nothing listening)".to_string() } else { e.to_string() }),
        Ok(mut s) => {
            let _ = s.set_read_timeout(Some(timeout));
            let mut buf = [0u8; 512];
            let mut got = 0;
            while got < buf.len() {
                match s.read(&mut buf[got..]) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        got += n;
                        if parse_ident(&buf[..got]).is_some() {
                            break;
                        }
                    }
                }
            }
            c.rtt_ms = Some(t0.elapsed().as_millis() as u64);
            match parse_ident(&buf[..got]) {
                Some(b) => {
                    c.ok = Some(true);
                    c.detail = format!("{}: {}", ip, b);
                }
                None => c.detail = format!("{}: connected, no SSH banner", ip),
            }
        }
    }
    c.at_ms = now_ms();
    c
}

/// One interface address of this phone.
#[derive(Clone, Debug, PartialEq)]
pub struct Iface {
    pub name: String,
    pub ip: IpAddr,
}

/// Every interface address (getifaddrs); empty when the call fails, never an error.
pub fn interfaces() -> Vec<Iface> {
    let mut out = vec![];
    unsafe {
        let mut head: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut head) != 0 {
            return out;
        }
        let mut p = head;
        while !p.is_null() {
            let a = &*p;
            if !a.ifa_addr.is_null() {
                let name = std::ffi::CStr::from_ptr(a.ifa_name).to_string_lossy().to_string();
                match (*a.ifa_addr).sa_family as i32 {
                    libc::AF_INET => {
                        let sa = &*(a.ifa_addr as *const libc::sockaddr_in);
                        out.push(Iface { name, ip: IpAddr::from(sa.sin_addr.s_addr.to_ne_bytes()) });
                    }
                    libc::AF_INET6 => {
                        let sa = &*(a.ifa_addr as *const libc::sockaddr_in6);
                        out.push(Iface { name, ip: IpAddr::from(sa.sin6_addr.s6_addr) });
                    }
                    _ => {}
                }
            }
            p = a.ifa_next;
        }
        libc::freeifaddrs(head);
    }
    out
}

/// The WireGuard mesh address: an address inside a mesh CIDR, `wg0` first. IPv4 preferred (the 10.0.0.x address).
pub fn mesh_addr(ifs: &[Iface], cidrs: &[String]) -> Option<Iface> {
    let inside = |i: &&Iface| cidrs.iter().any(|c| crate::door::in_cidr(&i.ip, c));
    let mut c: Vec<&Iface> = ifs.iter().filter(inside).collect();
    c.sort_by_key(|i| (i.name != "wg0", !i.ip.is_ipv4()));
    c.first().map(|i| (*i).clone())
}

/// The Wi-Fi / LAN address: a private IPv4 on a non-tunnel, non-cellular interface that is not a mesh address.
pub fn lan_addr(ifs: &[Iface], cidrs: &[String]) -> Option<Iface> {
    ifs.iter()
        .filter(|i| !(i.name.starts_with("wg") || i.name.starts_with("tun") || i.name.starts_with("rmnet") || i.name == "lo" || i.name.starts_with("dummy")))
        .filter(|i| match i.ip {
            IpAddr::V4(a) => a.is_private() && !cidrs.iter().any(|c| crate::door::in_cidr(&i.ip, c)),
            _ => false,
        })
        .min_by_key(|i| !i.name.starts_with("wlan"))
        .cloned()
}

/// Which address checks a server needs: loopback always, mesh always, LAN only while Mesh-only is OFF.
pub fn required_addrs(mesh_only: bool) -> (bool, bool, bool) {
    (true, !mesh_only, true)
}

/// Run every check for one server. `process` is Some(alive) only for the engine this app hosts.
pub fn run_checks(port: u16, process: Option<bool>, mesh_only: bool, cidrs: &[String], timeout: Duration) -> Vec<Check> {
    let ifs = interfaces();
    let (_, lan_req, _) = required_addrs(mesh_only);
    let mut out = vec![];
    match process {
        Some(alive) => out.push(Check::new("process", true, Some(alive), if alive { "engine alive in this app" } else { "engine not running" })),
        None => out.push(Check::new("process", false, None, "another app's process: not visible from this uid")),
    }
    let t0 = Instant::now();
    let conn = TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], port)), timeout);
    let mut port_c = Check::new("port", true, Some(conn.is_ok()), &match &conn {
        Ok(_) => format!("127.0.0.1:{} accepts connections", port),
        Err(e) => format!("127.0.0.1:{}: {}", port, e),
    });
    port_c.rtt_ms = Some(t0.elapsed().as_millis() as u64);
    out.push(port_c);
    drop(conn);
    out.push(probe_addr("loopback", true, IpAddr::from([127, 0, 0, 1]), port, timeout));
    match lan_addr(&ifs, cidrs) {
        Some(i) => out.push(probe_addr("lan", lan_req, i.ip, port, timeout)),
        None => out.push(Check::new("lan", false, None, "no Wi-Fi / LAN address")),
    }
    match mesh_addr(&ifs, cidrs) {
        Some(i) => out.push(probe_addr("mesh", true, i.ip, port, timeout)),
        None => out.push(Check::new("mesh", true, Some(false), "no mesh address (wg0 down or not configured)")),
    }
    out
}

/// "ssh -p PORT user@mesh-ip", or with a placeholder when this phone has no mesh address.
pub fn ssh_command(user: &str, port: u16, mesh_ip: Option<&str>) -> String {
    format!("ssh -p {} {}@{}", port, if user.is_empty() { "user" } else { user }, mesh_ip.unwrap_or("<mesh-ip>"))
}

/// The JSON a door row carries (additive to the old fields): verdict, checks, which failed, mesh reachability.
pub fn status_json(port: u16, process: Option<bool>, mesh_only: bool, cidrs: &[String], timeout: Duration, user: &str) -> Value {
    let checks = run_checks(port, process, mesh_only, cidrs, timeout);
    let (v, failed) = verdict(&checks);
    let ifs = interfaces();
    let mesh = mesh_addr(&ifs, cidrs);
    let mesh_ok = checks.iter().find(|c| c.id == "mesh").and_then(|c| c.ok);
    let reason = match v {
        Verdict::Amber => format!("failed: {}", failed.join(", ")),
        Verdict::Red => "nothing answers".to_string(),
        Verdict::Grey => "not checked yet".to_string(),
        Verdict::Green => "all required checks passed".to_string(),
    };
    json!({
        "verdict": v.word(), "label": v.label(), "failed": failed, "reason": reason,
        "mesh_reachable": mesh_ok, "mesh_ip": mesh.as_ref().map(|i| i.ip.to_string()), "lan_ip": lan_addr(&ifs, cidrs).map(|i| i.ip.to_string()),
        "checks": checks.iter().map(Check::to_json).collect::<Vec<_>>(),
        "ssh_command": ssh_command(user, port, mesh.as_ref().map(|i| i.ip.to_string()).as_deref()),
        "checked_at_ms": now_ms(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vectors() -> Value {
        serde_json::from_str(include_str!("../../data/sshd-verdict-vectors.json")).expect("vectors parse")
    }

    #[test]
    fn the_verdict_matches_every_shared_vector() {
        for case in vectors()["cases"].as_array().unwrap() {
            let checks: Vec<Check> = case["checks"].as_array().unwrap().iter().map(|c| Check::new(c[0].as_str().unwrap(), c[1].as_bool().unwrap(), c[2].as_bool(), "")).collect();
            let (v, failed) = verdict(&checks);
            assert_eq!(v.word(), case["verdict"].as_str().unwrap(), "{}", case["name"]);
            let want: Vec<String> = case["failed"].as_array().unwrap().iter().map(|s| s.as_str().unwrap().to_string()).collect();
            assert_eq!(failed, want, "{}", case["name"]);
        }
    }

    #[test]
    fn the_banner_parser_matches_every_shared_vector() {
        for b in vectors()["banners"].as_array().unwrap() {
            let got = parse_ident(b["bytes"].as_str().unwrap().as_bytes());
            assert_eq!(got.as_deref(), b["ident"].as_str(), "{}", b["bytes"]);
        }
    }

    #[test]
    fn labels_are_words_not_colours_alone() {
        assert_eq!(Verdict::Green.label(), "UP");
        assert_eq!(Verdict::Amber.label(), "PARTIAL");
        assert_eq!(Verdict::Red.label(), "OFF");
        assert_eq!(Verdict::Grey.label(), "NOT CHECKED");
    }

    fn ifc(n: &str, ip: &str) -> Iface {
        Iface { name: n.to_string(), ip: ip.parse().unwrap() }
    }

    #[test]
    fn mesh_and_lan_addresses_are_picked_from_the_interfaces() {
        let cidrs: Vec<String> = ["10.0.0.0/24", "fd0c:1d00::/32"].iter().map(|s| s.to_string()).collect();
        let ifs = vec![ifc("lo", "127.0.0.1"), ifc("rmnet0", "10.55.1.2"), ifc("wlan0", "192.168.1.20"), ifc("wg0", "10.0.0.7"), ifc("wg0", "fd0c:1d00::7")];
        assert_eq!(mesh_addr(&ifs, &cidrs).unwrap().ip.to_string(), "10.0.0.7");
        assert_eq!(lan_addr(&ifs, &cidrs).unwrap().ip.to_string(), "192.168.1.20");
        assert!(mesh_addr(&[ifc("wlan0", "192.168.1.20")], &cidrs).is_none());
        assert!(lan_addr(&[ifc("wg0", "10.0.0.7")], &cidrs).is_none());
    }

    #[test]
    fn the_ssh_command_names_the_mesh_address() {
        assert_eq!(ssh_command("webserver", 8025, Some("10.0.0.7")), "ssh -p 8025 webserver@10.0.0.7");
        assert_eq!(ssh_command("", 8024, None), "ssh -p 8024 user@<mesh-ip>");
    }

    #[test]
    fn a_closed_port_is_off_and_a_real_banner_is_green_on_loopback() {
        // closed: bind then drop to get a free port
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let c = probe_addr("loopback", true, "127.0.0.1".parse().unwrap(), port, Duration::from_millis(300));
        assert_eq!(c.ok, Some(false));
        // a fake server that sends a banner
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let p = l.local_addr().unwrap().port();
        std::thread::spawn(move || {
            if let Ok((mut s, _)) = l.accept() {
                use std::io::Write;
                let _ = s.write_all(b"SSH-2.0-test_1\r\n");
            }
        });
        let c = probe_addr("loopback", true, "127.0.0.1".parse().unwrap(), p, Duration::from_millis(800));
        assert_eq!(c.ok, Some(true), "{}", c.detail);
        // a server that accepts but says nothing useful is NOT up
        let l2 = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let p2 = l2.local_addr().unwrap().port();
        std::thread::spawn(move || {
            if let Ok((mut s, _)) = l2.accept() {
                use std::io::Write;
                let _ = s.write_all(b"HTTP/1.1 400 Bad\r\n\r\n");
            }
        });
        let c = probe_addr("loopback", true, "127.0.0.1".parse().unwrap(), p2, Duration::from_millis(800));
        assert_eq!(c.ok, Some(false), "a listener without an SSH banner is not an sshd");
    }
}
