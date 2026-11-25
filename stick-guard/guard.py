#!/usr/bin/env python3
"""couch-tv stick guard: small extras for a Google TV run by a couch-tv bridge on an always-on
Linux box (here a 4G USB modem stick running Debian). Runs as couch-tv-adb-guard.service.

1. Port 5555 closed after a full TV restart and the TV announces Wireless debugging: find its port
   over mDNS (a browser that runs all the time, so it is seen within seconds), `adb connect` (this
   computer is already trusted, no code) and `adb tcpip 5555`. The TV helper app switches Wireless
   debugging back on by itself after each restart (tv-helper Keeper.kt).
2. Whenever the stick (re)connects, the screen comes on, or the Google TV home comes forward, and
   the Google TV home is still in front two seconds later: start couch-tv. Never covers live TV,
   HDMI or any other app.
3. "Who's watching?" in TV+ and Prime Video: picks one shared profile (config "profile").
   Listens to the TV's activity events (no polling); acts once per time the app comes forward.
4. Power cut: the TV is up but ADB stays closed for 3 minutes -> a notification for the phone app
   (and optionally ntfy) telling the family to switch Wireless debugging on.
5. Sleep protection: in the night window, a TV with no key press for an hour is put to sleep.
6. App hub on port 5401: the phone app's download page, updates and notifications, plus the TV
   helper app's media position, seeking, app icons and the red-button "last channel" key.

Settings: config.json next to this file (config.example.json lists every key). The TV address
falls back to the first TV in couch-tv's own config.json.
"""
import ipaddress, json, os, re, secrets, socket, subprocess, threading, time, urllib.request
from zeroconf import Zeroconf, ServiceBrowser

HERE = os.path.dirname(os.path.abspath(__file__))


def load_config():
    cfg = {}
    try:
        cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    except (OSError, ValueError):
        pass
    if not cfg.get("tv_ip"):
        try:
            ct = json.load(open(os.path.expanduser(cfg.get("couch_tv_config", "~/couch-tv/config.json")), encoding="utf-8"))
            tvs = ct.get("tvs") or []
            tv = next((t for t in tvs if t.get("id") == ct.get("default_tv")), tvs[0] if tvs else {})
            cfg["tv_ip"] = tv.get("ip")
        except (OSError, ValueError):
            pass
    return cfg


CFG = load_config()
TV = CFG.get("tv_ip") or ""
FIXED = int(CFG.get("adb_port", 5555))
S = f"{TV}:{FIXED}"
HOME_ACT = "app.couchtv.launcher/.MainActivity"
# The Google TV home itself (not its profile, account or settings screens).
GOOGLE_HOMES = ("com.google.android.apps.tv.launcherx/.home.HomeActivity",
                "com.google.android.apps.tv.launcherx/.home.VanillaModeHomeActivity")

# "Who's watching?": the shared profile's name (empty = leave every profile screen alone).
PROFILE = (CFG.get("profile") or "").strip().lower()
TVPLUS_PICKER = "com.turkcell.ott/.androidtv.presentation.ui.profile.select.TvMultiProfileSelectActivity"
PRIME_ACT = "com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity"
# Prime Video draws its own screen (no accessibility tree). Its profile list is a fixed column on
# the left, so the avatars' colours tell the screen apart: config "prime_profiles" gives
# {"marks": [[x, y, [r, g, b]], ...], "index": n}, n being the shared profile's place (from 0).
_pp = CFG.get("prime_profiles") or {}
PRIME_MARKS = [((m[0], m[1]), tuple(m[2])) for m in _pp.get("marks", [])]
PRIME_TV_INDEX = int(_pp.get("index", 0))
TOL = 40

_night = CFG.get("sleep_guard") or {}
NIGHT_ON = _night.get("enabled", True)
NIGHT_FROM, NIGHT_TO, IDLE_MIN = int(_night.get("from", 3)), int(_night.get("to", 6)), int(_night.get("idle_minutes", 60))
ALERT_AFTER = 180                               # seconds the TV is up with ADB closed

configured_boot = None
seen_boot = None
night_done = None
down_since = None
alerted = False
connected = False
wake = threading.Event()          # set to run the main loop at once (TV announced, screen on)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def adb(*args, timeout=15):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout + r.stderr).strip()
    except subprocess.TimeoutExpired:
        return "timeout"


def sh(cmd, timeout=15):
    return adb("-s", S, "shell", cmd, timeout=timeout)


def port_open(port):
    try:
        with socket.create_connection((TV, port), timeout=2):
            return True
    except OSError:
        return False


def pings():
    return subprocess.run(["ping", "-c1", "-W1", TV], capture_output=True).returncode == 0


# ---------- phone notifications (ntfy.sh) ----------
def topic():
    p = os.path.join(HERE, "ntfy-topic")
    if not os.path.exists(p):
        with open(p, "w") as f:
            f.write("couchtv-" + secrets.token_hex(8))
    return open(p).read().strip()


NTFY = bool(CFG.get("ntfy"))   # off unless asked for: notifications go to the phone app (app hub)


def notify(title, message, tags="tv"):
    add_event(title, message, tags)
    if not NTFY:
        return
    body = json.dumps({"topic": topic(), "title": title, "message": message, "tags": [tags]}).encode()
    try:
        urllib.request.urlopen(urllib.request.Request("https://ntfy.sh/", data=body,
                               headers={"Content-Type": "application/json"}), timeout=10)
        log("bildirim:", title)
    except Exception as e:
        log("bildirim gonderilemedi:", e)


# ---------- app hub: the phone app's downloads, version and notifications (port 5401) ----------
# ~/couch-tv-apphub/ holds couch-tv-kumanda.apk and version.json (put there by phone-app/deploy.sh
# on the PC) and events.jsonl (one JSON notification per line, written here or by the deploy).
HUB = os.path.expanduser("~/couch-tv-apphub")
HUB_PORT = 5401
EVENTS = os.path.join(HUB, "events.jsonl")
_ev_lock = threading.Lock()


def add_event(title, message, kind="tv"):
    os.makedirs(HUB, exist_ok=True)
    ev = {"id": int(time.time() * 1000), "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
          "kind": kind, "title": title, "message": message}
    with _ev_lock:
        lines = []
        if os.path.exists(EVENTS):
            lines = open(EVENTS, encoding="utf-8").read().splitlines()[-49:]
        lines.append(json.dumps(ev, ensure_ascii=False))
        tmp = EVENTS + ".tmp"
        open(tmp, "w", encoding="utf-8").write("\n".join(lines) + "\n")
        os.replace(tmp, EVENTS)
    log("bildirim:", title)


# ---------- the TV helper app (app.couchtv.helper): media position, seeking, app icons ----------
HELPER = "app.couchtv.helper/.Cmd"
ICONS = os.path.join(HUB, "icons")
PKG_RE = re.compile(r"^[A-Za-z0-9_.]{3,120}$")


def helper(action, extra="", timeout=8):
    """Sends one command to the helper over ADB and returns its JSON answer, or None."""
    out = sh(f"am broadcast -f 32 -n {HELPER} -a app.couchtv.helper.{action} {extra}", timeout=timeout)
    m = re.search(r'data="(.*)"\s*$', out, re.S)
    try:
        return json.loads(m.group(1)) if m else None
    except ValueError:
        return None


def app_icon(pkg):
    """PNG bytes of an app's icon, from the stick's cache or (once) from the helper."""
    path = os.path.join(ICONS, pkg + ".png")
    if not os.path.exists(path):
        j = helper("ICON", f"--es pkg {pkg} --ei size 128", timeout=12)
        if not j or not j.get("png"):
            return None
        import base64
        os.makedirs(ICONS, exist_ok=True)
        open(path, "wb").write(base64.b64decode(j["png"]))
    return open(path, "rb").read()


INDEX_HTML = """<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Salon TV uygulaması</title>
<style>body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:24px;max-width:520px}
a.b{display:block;background:#D85A30;color:#fff;text-align:center;padding:18px;border-radius:14px;
font-size:20px;text-decoration:none;margin:24px 0}small{color:#aaa}</style></head><body>
<h1>Salon TV</h1><p>Telefon kumandası uygulaması. Sürüm: <b>%(name)s</b></p>
<a class="b" href="/app/couch-tv-kumanda.apk">Uygulamayı indir</a>
<p><small>İndirince açın. Telefon "bilinmeyen kaynaklardan yükleme" izni isterse tarayıcınıza
bir kez izin verin. Sonraki güncellemeler uygulamanın içinden gelir.</small></p>
<p><small>Uygulama olmadan: <a href="http://%(host)s:5400" style="color:#8cf">web kumandası</a></small></p>
</body></html>"""


STATE_TR = {"ok": ("✓", "#2e9d5b"), "warn": ("!", "#c98a14"), "bad": ("✕", "#d2453b"), "unknown": ("?", "#888")}


def status_html(st):
    import html
    rows, group = [], None
    for i in st["items"]:
        if i["group"] != group:
            group = i["group"]
            rows.append(f"<h2>{html.escape(group)}</h2>")
        mark, colour = STATE_TR.get(i["state"], STATE_TR["unknown"])
        hint = f"<div class=h>{html.escape(i['hint'])}</div>" if i.get("hint") else ""
        rows.append(f"<div class=r><b style='color:{colour}'>{mark}</b><span>{html.escape(i['label'])}"
                    f"<small>{html.escape(i['detail'])}</small>{hint}</span></div>")
    return ("<!doctype html><html lang=tr><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            "<meta http-equiv=refresh content=30><title>Salon TV durumu</title><style>body{font-family:sans-serif;background:#111;"
            "color:#eee;margin:0;padding:16px;max-width:560px}h2{font-size:15px;color:#aaa;margin:20px 0 6px}.r{display:flex;"
            "gap:12px;padding:8px 0;border-bottom:1px solid #222}.r b{width:20px;text-align:center}small{display:block;color:#aaa}"
            ".h{color:#e8b04a;font-size:13px;margin-top:2px}</style></head><body><h1>Salon TV durumu</h1>"
            + "".join(rows) + f"<p><small>{html.escape(st['updated'])}</small></p></body></html>")


def serve_hub():
    from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
    from urllib.parse import urlparse, parse_qs

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, body, ctype, extra=None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            origin = allowed_origin(self.headers.get("Origin"), self.headers.get("Host"))
            if origin:                       # only the bridge's own page (the remote, port 5400)
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Vary", "Origin")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def version(self):
            try:
                return json.load(open(os.path.join(HUB, "version.json"), encoding="utf-8"))
            except Exception:
                return None

        def refuse(self):
            """True (and answered) for a caller outside the home network, or a browser page that
            is not the bridge's own."""
            if not private_client(self.client_address[0]):
                self.send(403, b"home network only", "text/plain")
                return True
            if self.headers.get("Origin") and not allowed_origin(self.headers.get("Origin"), self.headers.get("Host")):
                self.send(403, b"origin not allowed", "text/plain")
                return True
            return False

        def do_HEAD(self):
            self.do_GET()

        def do_OPTIONS(self):
            self.send(204, b"", "text/plain", {"Access-Control-Allow-Methods": "GET, POST, OPTIONS",
                                                 "Access-Control-Allow-Headers": "Content-Type"})

        def do_POST(self):
            if self.refuse():
                return
            if not (self.headers.get("Content-Type") or "").startswith("application/json"):
                return self.send(415, b'{"ok": false, "error": "json only"}', "application/json")
            u = urlparse(self.path)
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}") if n < 10000 else {}
            except ValueError:
                body = {}
            if u.path == "/key":
                # Only the keys the TV helper is allowed to ask for (red button -> last channel).
                key = body.get("key")
                if self.client_address[0] not in (TV, "127.0.0.1"):
                    return self.send(403, b'{"ok": false, "error": "from the TV only"}', "application/json")
                if key not in ("LAST_CHANNEL",):
                    return self.send(400, b'{"ok": false, "error": "key"}', "application/json")
                sh(f"input keyevent KEYCODE_{key}", timeout=6)
                log("tus:", key)
                return self.send(200, b'{"ok": true}', "application/json")
            if u.path == "/seek":
                pos = body.get("position_ms")
                if not isinstance(pos, (int, float)) or pos < 0:
                    return self.send(400, b'{"ok": false, "error": "position_ms"}', "application/json")
                pkg = body.get("package")
                extra = f"--el pos {int(pos)}" + (f" --es pkg {pkg}" if isinstance(pkg, str) and PKG_RE.match(pkg) else "")
                j = helper("SEEK", extra) or {"ok": False, "error": "helper did not answer"}
                return self.send(200 if j.get("ok") else 502, json.dumps(j).encode(), "application/json")
            self.send(404, b"not found", "text/plain")

        def do_GET(self):
            if self.refuse():
                return
            u = urlparse(self.path)
            if u.path in ("/", "/app", "/app/"):
                v = self.version() or {"versionName": "-"}
                host = self.headers.get("Host", "").split(":")[0] or my_ip()
                html = INDEX_HTML % {"name": v.get("versionName", "-"), "host": host}
                return self.send(200, html.encode(), "text/html; charset=utf-8")
            if u.path == "/app/version.json":
                v = self.version()
                return self.send(200 if v else 404, json.dumps(v or {}).encode(), "application/json")
            if u.path == "/app/couch-tv-kumanda.apk":
                p = os.path.join(HUB, "couch-tv-kumanda.apk")
                if not os.path.exists(p):
                    return self.send(404, b"no apk", "text/plain")
                body = open(p, "rb").read()
                return self.send(200, body, "application/vnd.android.package-archive",
                                 {"Content-Disposition": 'attachment; filename="couch-tv-kumanda.apk"'})
            if u.path == "/events":
                since = int((parse_qs(u.query).get("since") or ["0"])[0] or 0)
                evs = []
                if os.path.exists(EVENTS):
                    for line in open(EVENTS, encoding="utf-8"):
                        try:
                            e = json.loads(line)
                        except ValueError:
                            continue
                        if e.get("id", 0) > since:
                            evs.append(e)
                latest = evs[-1]["id"] if evs else since
                return self.send(200, json.dumps({"events": evs[-20:], "latest": latest},
                                                 ensure_ascii=False).encode(), "application/json; charset=utf-8")
            if u.path == "/media":
                j = helper("INFO") or {"ok": False, "error": "helper did not answer"}
                return self.send(200 if j.get("ok") else 502, json.dumps(j, ensure_ascii=False).encode(),
                                 "application/json; charset=utf-8")
            if u.path == "/icon":
                pkg = (parse_qs(u.query).get("pkg") or [""])[0]
                png = app_icon(pkg) if PKG_RE.match(pkg) else None
                if not png:
                    return self.send(404, b"no icon", "text/plain")
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(png)))
                self.send_header("Cache-Control", "max-age=604800")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(png)
                return
            if u.path == "/status":
                return self.send(200, json.dumps(status(), ensure_ascii=False).encode(), "application/json; charset=utf-8")
            if u.path == "/status.html":
                return self.send(200, status_html(status()).encode(), "text/html; charset=utf-8")
            if u.path == "/health":
                return self.send(200, b'{"ok": true}', "application/json")
            self.send(404, b"not found", "text/plain")

    os.makedirs(HUB, exist_ok=True)
    ThreadingHTTPServer(("0.0.0.0", HUB_PORT), H).serve_forever()


def my_ip():
    """This computer's address on the TV's network (the one the TV and phones reach it on)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as u:
            u.connect((TV, 9))
            return u.getsockname()[0]
    except OSError:
        return ""


# ---------- 1. keep 5555 open ----------
tls_port = None                   # the TV's current Wireless debugging port, from the browser below


def browse_wireless():
    """Listens for the TV's Wireless debugging announcement all the time, so a TV that just
    restarted is reconnected within seconds instead of at the next poll."""
    class L:
        def add_service(self, zc, t, name):
            global tls_port
            info = zc.get_service_info(t, name, 3000)
            if info and TV in info.parsed_addresses():
                if tls_port != info.port:
                    log("Kablosuz hata ayiklama duyuruldu, port", info.port)
                tls_port = info.port
                wake.set()
        update_service = add_service

        def remove_service(self, zc, t, name):
            global tls_port
            tls_port = None

    while True:
        zc = Zeroconf()
        try:
            ServiceBrowser(zc, "_adb-tls-connect._tcp.local.", L())
            time.sleep(6 * 3600)  # start afresh now and then, in case the network changed
        except Exception as e:
            log("mDNS hatasi:", e)
            time.sleep(30)
        finally:
            zc.close()


def wireless_port(wait=6):
    found = []

    class L:
        def add_service(self, zc, t, name):
            info = zc.get_service_info(t, name, 3000)
            if info and TV in info.parsed_addresses():
                found.append(info.port)
        def update_service(self, *a): pass
        def remove_service(self, *a): pass

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, "_adb-tls-connect._tcp.local.", L())
        end = time.time() + wait
        while time.time() < end and not found:
            time.sleep(0.3)
    finally:
        zc.close()
    return found[0] if found else None


def reopen_5555():
    p = tls_port or wireless_port(3)
    if not p:
        return False
    log("5555 kapali, Kablosuz hata ayiklama portu", p, "-> 5555 aciliyor")
    log(adb("connect", f"{TV}:{p}"))
    log(adb("-s", f"{TV}:{p}", "tcpip", str(FIXED)))
    for _ in range(10):
        time.sleep(1)
        if port_open(FIXED):
            break
    adb("disconnect", f"{TV}:{p}")
    return port_open(FIXED)


# ---------- 2. couch-tv in front, and the helper told where the hub is ----------
def configure_helper():
    """Once per TV boot: tell the helper where the hub is, and make sure it may switch Wireless
    debugging back on after the next restart (the grant survives restarts and updates; this only
    puts it back if someone took it away)."""
    global configured_boot, seen_boot
    m = re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                  sh("cat /proc/sys/kernel/random/boot_id"))
    boot = m.group(0) if m else None   # just the id: adb can add its own lines around it
    if not boot or boot == configured_boot:
        return
    if boot != seen_boot:
        if seen_boot is not None:
            log("TV yeniden basladi")
        seen_boot = boot
    sh("pm grant app.couchtv.helper android.permission.WRITE_SECURE_SETTINGS")
    ip = my_ip()
    if ip and "result=1" in sh(f"am broadcast -f 32 -n app.couchtv.helper/.Cmd -a app.couchtv.helper.CONFIG --es hub {ip}:{HUB_PORT}"):
        log("yardimci ayarlandi:", f"{ip}:{HUB_PORT}")
        configured_boot = boot


_home_lock = threading.Lock()
_home_last = 0.0


def google_home_in_front():
    top = sh("dumpsys activity activities | grep -m1 topResumedActivity", timeout=8)
    return any(h in top for h in GOOGLE_HOMES)


def bring_couch_tv(reason, delay=0):
    """couch-tv in front if the Google TV home is, and still is two seconds later (the TV's own
    Home button watch usually gets there first; this is the stick's backup). Anything else in
    front (live TV, HDMI, YouTube, Settings) is left alone."""
    global _home_last
    if delay:
        time.sleep(delay)
    if not _home_lock.acquire(blocking=False):
        return
    try:
        if time.time() - _home_last < 5 or not google_home_in_front():
            return
        time.sleep(2)
        if not google_home_in_front():
            return
        sh(f"am start -n {HOME_ACT}")
        _home_last = time.time()
        log(f"Google TV paneli onde ({reason}), couch-tv acildi")
    finally:
        _home_lock.release()


def bring_later(reason, delay):
    threading.Thread(target=bring_couch_tv, args=(reason, delay), daemon=True).start()


# ---------- 3. "who's watching" -> tv ----------
def keys(*codes):
    sh("input keyevent " + " ".join(str(c) for c in codes))


def tvplus_index():
    cache = os.path.join(HERE, "tvplus-index")
    if os.path.exists(cache) and time.time() - os.path.getmtime(cache) < 7 * 86400:
        return int(open(cache).read())
    sh("uiautomator dump /sdcard/couchtv-ui.xml", timeout=30)
    xml = sh("cat /sdcard/couchtv-ui.xml; rm -f /sdcard/couchtv-ui.xml", timeout=15)
    for m in re.finditer(r'text="([^"]*)" resource-id="text_profile_(\d+)"', xml):
        if m.group(1).strip().lower() == PROFILE:
            open(cache, "w").write(m.group(2))
            return int(m.group(2))
    return None


def pick_tvplus():
    if not PROFILE:
        return
    time.sleep(1.5)
    i = tvplus_index()
    if i is None:
        log("TV+: 'tv' profili bulunamadi")
        return
    if TVPLUS_PICKER not in sh("dumpsys activity activities | grep -m1 topResumedActivity"):
        return             # someone already picked a profile
    keys(*([21] * 6 + [22] * i + [23]))   # LEFT x6, RIGHT x i, OK
    log("TV+: tv profili secildi")


def prime_on_profiles():
    cmd = "screencap /data/local/tmp/couchtv.raw; W=$(od -An -tu4 -N4 /data/local/tmp/couchtv.raw)"
    for (x, y), _ in PRIME_MARKS:
        cmd += f"; dd if=/data/local/tmp/couchtv.raw bs=1 skip=$((16 + ({y}*W + {x})*4)) count=3 2>/dev/null | od -An -tu1"
    cmd += "; rm -f /data/local/tmp/couchtv.raw"
    nums = [int(n) for n in sh(cmd, timeout=20).split() if n.isdigit()]
    if len(nums) != 3 * len(PRIME_MARKS):
        return False
    for k, (_, want) in enumerate(PRIME_MARKS):
        got = nums[3 * k:3 * k + 3]
        if any(abs(g - w) > TOL for g, w in zip(got, want)):
            return False
    return True


def pick_prime():
    if not PROFILE or not PRIME_MARKS:
        return
    end = time.time() + 30
    while time.time() < end:
        time.sleep(2)
        if PRIME_ACT not in sh("dumpsys activity activities | grep -m1 topResumedActivity"):
            return
        if prime_on_profiles():
            time.sleep(1)
            keys(*([19] * 4 + [20] * PRIME_TV_INDEX + [23]))   # UP x4, DOWN x index, OK
            log("Prime: Tv profili secildi")
            return


def watch_events():
    """Follow the TV's 'activity came to the front' events; restart the stream when it drops."""
    busy = set()
    while True:
        if not port_open(FIXED):
            time.sleep(3)
            continue
        adb("connect", S)
        p = subprocess.Popen(["adb", "-s", S, "logcat", "-b", "events", "-T", "1", "-v", "brief"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        try:
            for line in p.stdout:
                if "screen_toggled: 1" in line:
                    # woke from standby: whatever the TV puts on screen first, look a moment later
                    bring_later("ekran acildi", 3)
                    continue
                if "wm_set_resumed_activity" not in line:
                    continue
                if any(h in line for h in GOOGLE_HOMES):
                    bring_later("ana ekran", 0)
                    continue
                for act, fn in ((TVPLUS_PICKER, pick_tvplus), (PRIME_ACT, pick_prime)):
                    if act in line and act not in busy:
                        busy.add(act)
                        def run(a=act, f=fn):
                            try:
                                f()
                            except Exception as e:
                                log("profil hatasi:", e)
                            finally:
                                busy.discard(a)
                        threading.Thread(target=run, daemon=True).start()
        finally:
            p.kill()
        time.sleep(3)


# ---------- 5. sleep protection ----------
def night_check():
    global night_done
    if not NIGHT_ON:
        return
    now = time.localtime()
    if not (NIGHT_FROM <= now.tm_hour < NIGHT_TO):
        return
    today = time.strftime("%Y-%m-%d")
    if night_done == today:
        return
    power = sh("dumpsys power | grep -E 'mWakefulness=|mLastUserActivityTime'; cat /proc/uptime")
    if "mWakefulness=Awake" not in power:
        return
    m = re.search(r"mLastUserActivityTime\S*=(\d+)", power)
    up = re.search(r"^(\d+)\.\d+ ", power, re.M)
    if not (m and up):
        return
    idle_min = (int(up.group(1)) * 1000 - int(m.group(1))) / 60000
    if idle_min >= IDLE_MIN:
        log(f"uyuyakalma korumasi: {idle_min:.0f} dk dokunulmadi, TV kapatiliyor")
        keys("KEYCODE_SLEEP")
        night_done = today


# ---------- 7. status, self-repair, couch-tv updates ----------
# GET /status (hub) answers what is fine and what is not, for the phone app's System status page.
# The stick part is read on each request (cached 20 s); the TV part on every connection and every
# few hours, when the guard also puts back what a Google TV update can switch off.
HELPER_PKG = "app.couchtv.helper"
LAUNCHER_PKG = "app.couchtv.launcher"
A11Y_SERVICES = [f"{LAUNCHER_PKG}/{LAUNCHER_PKG}.FireWatch", f"{HELPER_PKG}/{HELPER_PKG}.ChannelKey"]
LISTENER = f"{HELPER_PKG}/{HELPER_PKG}.MediaListener"
AMBIENT_PKG = "com.google.android.apps.tv.dreamx"
SCREENSAVER = CFG.get("screensaver", "com.neilturner.aerialviews/.ui.screensaver.DreamActivity")
REPAIR = CFG.get("self_repair", True)
TV_CHECK_EVERY = 6 * 3600
UPSTREAM_EVERY = 7 * 86400
REPO = os.path.dirname(HERE)            # ~/couch-tv

_status_lock = threading.Lock()
tv_status = {"items": [], "checked": None}
stick_cache = {"items": [], "at": 0}
last_tv_ok = None                        # time the TV last answered over ADB
last_tv_check = 0
bad_since = {}                           # item id -> time it went bad (for one notification each)
upstream = {"behind": None, "checked": None}


def item(iid, group, label, state, detail="", hint=""):
    return {"id": iid, "group": group, "label": label, "state": state, "detail": detail, "hint": hint}


def run(*cmd, timeout=10):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except Exception:
        return ""


def stick_items():
    if time.time() - stick_cache["at"] < 20:
        return stick_cache["items"]
    out = []
    up = float(open("/proc/uptime").read().split()[0])
    out.append(item("uptime", "Stick", "Açık kalma süresi", "ok", f"{up / 86400:.1f} gün"))
    temps = []
    for z in os.listdir("/sys/class/thermal"):
        try:
            temps.append(int(open(f"/sys/class/thermal/{z}/temp").read()) / 1000)
        except (OSError, ValueError):
            pass
    if temps:
        t = max(temps)
        out.append(item("temp", "Stick", "Sıcaklık", "ok" if t < 70 else "warn" if t < 80 else "bad",
                        f"{t:.0f} °C", "" if t < 70 else "Stick çok ısınıyor: havalandırılan bir yere al."))
    mem = {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo") if ":" in l and l.split()[1].isdigit()}
    avail = mem.get("MemAvailable", 0) // 1024
    out.append(item("mem", "Stick", "Boş bellek", "ok" if avail > 80 else "warn" if avail > 40 else "bad",
                    f"{avail} MB", "" if avail > 80 else "Bellek azaldı; stick'i yeniden başlatmak iyi gelir."))
    st = os.statvfs("/")
    free_pct = st.f_bavail * 100 / st.f_blocks
    out.append(item("disk", "Stick", "Boş disk", "ok" if free_pct > 15 else "warn" if free_pct > 5 else "bad",
                    f"%{free_pct:.0f} ({st.f_bavail * st.f_frsize // 1048576} MB)"))
    try:
        sectors = int(open("/sys/block/mmcblk0/stat").read().split()[6])
        per_day = sectors * 512 / 1048576 / max(up / 86400, 0.05)
        out.append(item("writes", "Stick", "Disk yazma", "ok" if per_day < 1024 else "warn",
                        f"günde ~{per_day:.0f} MB", "" if per_day < 1024 else "eMMC çok yazılıyor; kaynağına bakılmalı."))
    except (OSError, ValueError, IndexError):
        pass
    sig = re.search(r"^\*:(\d+)", run("nmcli", "-t", "-f", "IN-USE,SIGNAL", "dev", "wifi"), re.M)
    if sig:
        s = int(sig.group(1))
        out.append(item("wifi", "Stick", "Wi-Fi sinyali", "ok" if s >= 50 else "warn" if s >= 30 else "bad",
                        f"%{s}", "" if s >= 50 else "Sinyal zayıf: stick'i modeme yaklaştır."))
    for unit, label in (("couch-tv-bridge", "Köprü servisi"), ("wifi-watchdog", "Wi-Fi bekçisi")):
        a = run("systemctl", "is-active", unit)
        out.append(item(unit, "Servisler", label, "ok" if a == "active" else "bad", a or "?",
                        "" if a == "active" else f"Stick'te: sudo systemctl restart {unit}"))
    try:
        h = json.loads(urllib.request.urlopen("http://127.0.0.1:5400/api/health", timeout=4).read())
        out.append(item("bridge", "Servisler", "Köprü cevap veriyor", "ok",
                        "TV bağlı" if h.get("tv_connected") else "TV bağlı değil"))
    except Exception:
        out.append(item("bridge", "Servisler", "Köprü cevap veriyor", "bad", "cevap yok",
                        "Stick'te: sudo systemctl restart couch-tv-bridge"))
    out.append(item("guard", "Servisler", "Nöbetçi", "ok", "çalışıyor"))
    bk = sorted(f for f in os.listdir(BACKUPS) if f.endswith(".tgz")) if os.path.isdir(BACKUPS) else []
    if bk:
        age = (time.time() - os.path.getmtime(os.path.join(BACKUPS, bk[-1]))) / 86400
        out.append(item("backup", "Stick", "Son yedek", "ok" if age < 2 else "warn", f"{age:.1f} gün önce ({len(bk)} yedek)",
                        "" if age < 2 else "Gece yedeği alınmıyor: stick'te systemctl status couch-tv-backup.timer"))
    else:
        out.append(item("backup", "Stick", "Son yedek", "warn", "yok", "Gece yedeği henüz alınmadı."))
    ver = run("git", "-C", REPO, "log", "-1", "--format=%h %cs")
    behind = upstream["behind"]
    out.append(item("version", "Sürüm", "Salon TV kodu", "ok" if not behind else "warn",
                    ver + (f" · couch-tv'de {behind} yeni değişiklik" if behind else ""),
                    "Bilgisayarda: scripts/sync-upstream.sh" if behind else ""))
    stick_cache.update(items=out, at=time.time())
    return out


def tv_checks():
    """Reads the TV's state and, with self_repair on, puts back what should be there."""
    out, fixed = [], []

    def fix(label, cmd):
        if REPAIR:
            sh(cmd, timeout=12)
            fixed.append(label)

    resolved = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    if LAUNCHER_PKG not in resolved:
        fix("couch-tv yeniden varsayılan ana ekran", f"cmd package set-home-activity {HOME_ACT}")
    out.append(item("home", "TV", "couch-tv ana ekran", "ok" if LAUNCHER_PKG in resolved or REPAIR else "bad",
                    "varsayılan" if LAUNCHER_PKG in resolved else ("düzeltildi" if REPAIR else "değil")))
    pkgs = sh("pm list packages")
    have_helper = f"package:{HELPER_PKG}" in pkgs
    enabled = sh("settings get secure enabled_accessibility_services").strip()
    present = [s for s in enabled.split(":") if s and s != "null"]
    want = [s for s in A11Y_SERVICES if have_helper or not s.startswith(HELPER_PKG)]
    missing = [s for s in want if s not in present]
    if missing:
        fix("erişilebilirlik dinleyicileri (Home, kırmızı tuş) yeniden açık",
            f"settings put secure enabled_accessibility_services {':'.join(present + missing)}; settings put secure accessibility_enabled 1")
    out.append(item("a11y", "TV", "Home ve kırmızı tuş dinleyicileri", "ok" if not missing or REPAIR else "bad",
                    "açık" if not missing else ("yeniden açıldı" if REPAIR else "kapalı: " + ", ".join(m.split(".")[-1] for m in missing))))
    if have_helper:
        listeners = sh("settings get secure enabled_notification_listeners")
        if LISTENER not in listeners:
            fix("yardımcının medya izni geri verildi", f"cmd notification allow_listener {LISTENER}")
        out.append(item("listener", "TV", "Yardımcının medya izni", "ok" if LISTENER in listeners or REPAIR else "bad",
                        "var" if LISTENER in listeners else ("geri verildi" if REPAIR else "yok"),
                        "" if LISTENER in listeners or REPAIR else "Zaman çubuğu ve İzle simgeleri çalışmaz."))
        perm = sh(f"dumpsys package {HELPER_PKG} | grep -m1 'WRITE_SECURE_SETTINGS: granted=true'")
        if "granted=true" not in perm:
            fix("yardımcının ayar izni geri verildi", f"pm grant {HELPER_PKG} android.permission.WRITE_SECURE_SETTINGS")
        hv = re.search(r"versionName=(\S+)", sh(f"dumpsys package {HELPER_PKG} | grep -m1 versionName"))
        out.append(item("helper", "TV", "TV yardımcısı", "ok", f"sürüm {hv.group(1) if hv else '?'}"))
    else:
        out.append(item("helper", "TV", "TV yardımcısı", "bad", "kurulu değil",
                        "Zaman çubuğu, kırmızı tuş ve otomatik yeniden bağlanma çalışmaz."))
    if f"package:{LAUNCHER_PKG}" in pkgs:
        lv = re.search(r"versionName=(\S+)", sh(f"dumpsys package {LAUNCHER_PKG} | grep -m1 versionName"))
        out.append(item("launcher", "TV", "couch-tv uygulaması", "ok", f"sürüm {lv.group(1) if lv else '?'}"))
    if f"package:{AMBIENT_PKG}" in pkgs:
        off = AMBIENT_PKG in sh("pm list packages -d")
        if not off:
            fix("reklamlı ambient mod yeniden kapatıldı", f"pm disable-user --user 0 {AMBIENT_PKG}")
        out.append(item("ambient", "TV", "Reklamlı ambient mod", "ok" if off or REPAIR else "warn",
                        "kapalı" if off else ("yeniden kapatıldı" if REPAIR else "açık")))
    ss_pkg = SCREENSAVER.split("/")[0]
    if SCREENSAVER and f"package:{ss_pkg}" in pkgs:
        cur = sh("settings get secure screensaver_components").strip()
        if cur != SCREENSAVER:
            fix("ekran koruyucu yeniden Aerial Views", f"settings put secure screensaver_components {SCREENSAVER}")
        out.append(item("screensaver", "TV", "Ekran koruyucu", "ok", "Aerial Views" if cur == SCREENSAVER or REPAIR else cur))
    free = re.search(r"Free RAM:\s*([\d,]+)K", sh("dumpsys meminfo | grep -m1 'Free RAM'", timeout=20))
    if free:
        mb = int(free.group(1).replace(",", "")) // 1024
        out.append(item("tvram", "TV", "TV boş bellek", "ok" if mb > 250 else "warn", f"{mb} MB"))
    if fixed:
        log("onarildi:", "; ".join(fixed))
        notify("Salon TV kendini onardı", "Bir güncellemeden sonra geri alındı: " + "; ".join(fixed) + ".", "wrench")
    return out


def check_tv(force=False):
    global last_tv_check
    if not force and time.time() - last_tv_check < TV_CHECK_EVERY:
        return
    last_tv_check = time.time()
    try:
        items = tv_checks()
    except Exception as e:
        log("TV kontrol hatasi:", e)
        return
    with _status_lock:
        tv_status.update(items=items, checked=time.time())


def tv_link_item():
    if connected:
        return item("tvlink", "TV", "TV bağlantısı", "ok", "bağlı (ADB)")
    seen = f"son görülme {time.strftime('%d.%m %H:%M', time.localtime(last_tv_ok))}" if last_tv_ok else "hiç görülmedi"
    if down_since:
        return item("tvlink", "TV", "TV bağlantısı", "bad", f"TV açık ama ADB kapalı ({seen})",
                    "TV'de: Ayarlar → Sistem → Geliştirici seçenekleri → Kablosuz hata ayıklama → Aç.")
    return item("tvlink", "TV", "TV bağlantısı", "ok", f"TV kapalı / beklemede ({seen})")


def status():
    items = stick_items() + [tv_link_item()] + list(tv_status["items"])
    worst = "bad" if any(i["state"] == "bad" for i in items) else "warn" if any(i["state"] == "warn" for i in items) else "ok"
    return {"ok": True, "state": worst, "updated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "tv_checked": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(tv_status["checked"])) if tv_status["checked"] else None,
            "items": items}


def watch_status():
    """A problem on the stick that stays for 10 minutes becomes one notification (and one more
    when it clears). The TV's own problems are repaired or reported by tv_checks."""
    while True:
        time.sleep(300)
        try:
            for i in stick_items():
                if i["state"] == "bad":
                    first = bad_since.setdefault(i["id"], time.time())
                    if first and time.time() - first >= 600:
                        notify(f"Salon TV: {i['label']}", f"{i['detail']}. {i['hint']}".strip(), "warning")
                        bad_since[i["id"]] = 0          # told once
                elif i["id"] in bad_since:
                    if bad_since.pop(i["id"]) == 0:
                        notify(f"Salon TV: {i['label']} düzeldi", i["detail"], "white_check_mark")
        except Exception as e:
            log("durum hatasi:", e)


def check_upstream():
    """Once a week: are there new couch-tv changes upstream (Seagey/couch-tv) not in this branch?"""
    seen = os.path.join(HERE, "upstream-seen")
    while True:
        try:
            run("git", "-C", REPO, "fetch", "-q", "origin", "main", timeout=120)
            head = run("git", "-C", REPO, "rev-parse", "origin/main")
            n = run("git", "-C", REPO, "rev-list", "--count", "HEAD..origin/main")
            upstream.update(behind=int(n) if n.isdigit() else None, checked=time.time())
            stick_cache["at"] = 0
            if n.isdigit() and int(n) > 0 and head and (not os.path.exists(seen) or open(seen).read().strip() != head):
                notify("couch-tv'de yenilik var", f"Seagey/couch-tv'de {n} yeni değişiklik var. "
                       "Bilgisayarda scripts/sync-upstream.sh ile alınabilir.", "package")
                open(seen, "w").write(head)
        except Exception as e:
            log("upstream hatasi:", e)
        time.sleep(UPSTREAM_EVERY)


# ---------- 8. hub safety ----------
# The hub answers the home network only. Browsers may call it only from the bridge's own page
# (port 5400), so another web site open on a phone cannot press keys or seek; the red-button key
# is accepted from the TV alone.
def private_client(addr):
    """A caller on the home network (private, loopback or link-local address)."""
    try:
        ip = ipaddress.ip_address(addr)
        return ip.is_private or ip.is_loopback or ip.is_link_local
    except ValueError:
        return False


def allowed_origin(origin, host_header):
    if not origin:
        return None
    host = (host_header or "").split(":")[0]
    ok = {f"http://{h}:5400" for h in (host, my_ip(), "localhost", "127.0.0.1") if h}
    return origin if origin in ok else None


BACKUPS = os.path.expanduser(CFG.get("backup_dir", "~/yedek"))


# ---------- main loop ----------
def tick():
    """One look at the TV. True when it is up and reachable over ADB."""
    global down_since, alerted, connected
    if not pings():
        down_since = None          # TV off or asleep: nothing to do
        if connected:
            log("TV agdan cikti (bekleme)")
        connected = False
        return False
    if not port_open(FIXED):
        connected = False
        if reopen_5555():
            pass
        else:
            down_since = down_since or time.time()
            if not alerted and time.time() - down_since > ALERT_AFTER:
                notify("TV: Kablosuz hata ayıklamayı açın",
                       "TV yeniden başladı ve stick bağlanamadı. TV'de: Ayarlar → Sistem → "
                       "Geliştirici seçenekleri → Kablosuz hata ayıklama → Aç. Kod gerekmez.", "warning")
                alerted = True
            return False
    down_since = None
    if "connected" not in adb("connect", S):
        return False
    global last_tv_ok
    last_tv_ok = time.time()
    if not connected:
        connected = True
        log("TV'ye baglanildi")
        configure_helper()
        bring_couch_tv("baglanti")
        check_tv(force=True)
    if alerted:
        notify("TV hazır", "Bağlantı geri geldi, telefon kumandası tam çalışıyor.", "white_check_mark")
        alerted = False
    configure_helper()
    night_check()
    check_tv()
    return True


if not TV:
    raise SystemExit("no TV address: set tv_ip in config.json, or a TV in couch-tv's config.json")
log("couch-tv guard basladi, TV", TV)
threading.Thread(target=watch_events, daemon=True).start()
threading.Thread(target=serve_hub, daemon=True).start()
threading.Thread(target=browse_wireless, daemon=True).start()
threading.Thread(target=watch_status, daemon=True).start()
threading.Thread(target=check_upstream, daemon=True).start()
while True:
    try:
        ok = tick()
    except Exception as e:
        log("hata:", e)
        ok = False
    # Every 3 seconds while the TV is away or closed, so a TV coming back is caught at once;
    # every 15 seconds once it is reachable. An mDNS announcement wakes the loop straight away.
    wake.wait(15 if ok else 3)
    wake.clear()
