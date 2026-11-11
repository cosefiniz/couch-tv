#!/usr/bin/env python3
"""couch-tv stick guard: small extras for a Google TV run by a couch-tv bridge on an always-on
Linux box (here a 4G USB modem stick running Debian). Runs as couch-tv-adb-guard.service.

1. Port 5555 closed after a full TV restart and the TV announces Wireless debugging: find its port
   over mDNS, `adb connect` (this computer is already trusted, no code) and `adb tcpip 5555`.
2. Once per TV boot, when ADB works and the Google TV home is in front: start couch-tv.
   Never covers live TV, HDMI or any other app.
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
import json, os, re, secrets, socket, subprocess, threading, time, urllib.request
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
GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity"

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

done_boot = None
configured_boot = None
night_done = None
down_since = None
alerted = False


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
            self.send_header("Access-Control-Allow-Origin", "*")   # the remote is served from :5400
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

        def do_HEAD(self):
            self.do_GET()

        def do_OPTIONS(self):
            self.send(204, b"", "text/plain", {"Access-Control-Allow-Methods": "GET, POST, OPTIONS",
                                                 "Access-Control-Allow-Headers": "Content-Type"})

        def do_POST(self):
            u = urlparse(self.path)
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}") if n < 10000 else {}
            except ValueError:
                body = {}
            if u.path == "/key":
                # Only the keys the TV helper is allowed to ask for (red button -> last channel).
                key = body.get("key")
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
    p = wireless_port()
    if not p:
        return False
    log("5555 kapali, Kablosuz hata ayiklama portu", p, "-> 5555 aciliyor")
    log(adb("connect", f"{TV}:{p}"))
    log(adb("-s", f"{TV}:{p}", "tcpip", str(FIXED)))
    time.sleep(4)
    adb("disconnect", f"{TV}:{p}")
    return port_open(FIXED)


# ---------- 2. couch-tv in front after a boot, and the helper told where the hub is ----------
def configure_helper():
    global configured_boot
    boot = sh("cat /proc/sys/kernel/random/boot_id")
    if not boot or "error" in boot or boot == configured_boot:
        return
    ip = my_ip()
    if ip and "result=1" in sh(f"am broadcast -f 32 -n app.couchtv.helper/.Cmd -a app.couchtv.helper.CONFIG --es hub {ip}:{HUB_PORT}"):
        log("yardimci ayarlandi:", f"{ip}:{HUB_PORT}")
        configured_boot = boot


def boot_home():
    global done_boot
    boot = sh("cat /proc/sys/kernel/random/boot_id")
    if not boot or "error" in boot or boot == done_boot:
        return
    top = sh("dumpsys activity activities | grep -m1 topResumedActivity")
    if GOOGLE_HOME in top:
        log("Google TV paneli onde, couch-tv aciliyor")
        sh(f"am start -n {HOME_ACT}")
        done_boot = boot
    elif "com.android.tv.settings/" in top:
        return             # Settings open (e.g. switching Wireless debugging on): wait for the home
    elif "topResumedActivity" in top:
        done_boot = boot   # something else (couch-tv, live TV, an app) is in front: leave it


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
            time.sleep(20)
            continue
        adb("connect", S)
        p = subprocess.Popen(["adb", "-s", S, "logcat", "-b", "events", "-T", "1", "-v", "brief"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        try:
            for line in p.stdout:
                if "wm_set_resumed_activity" not in line:
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
        time.sleep(10)


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


# ---------- main loop ----------
def tick():
    global down_since, alerted
    if not pings():
        down_since = None          # TV off or asleep: nothing to do
        return
    if not port_open(FIXED):
        if reopen_5555():
            pass
        else:
            down_since = down_since or time.time()
            if not alerted and time.time() - down_since > ALERT_AFTER:
                notify("TV: Kablosuz hata ayıklamayı açın",
                       "TV yeniden başladı (elektrik kesintisi?). TV'de: Ayarlar → Sistem → "
                       "Geliştirici seçenekleri → Kablosuz hata ayıklama → Aç. Kod gerekmez.", "warning")
                alerted = True
            return
    down_since = None
    if "connected" not in adb("connect", S):
        return
    if alerted:
        notify("TV hazır", "Bağlantı geri geldi, telefon kumandası tam çalışıyor.", "white_check_mark")
        alerted = False
    boot_home()
    configure_helper()
    night_check()


if not TV:
    raise SystemExit("no TV address: set tv_ip in config.json, or a TV in couch-tv's config.json")
log("couch-tv guard basladi, TV", TV)
threading.Thread(target=watch_events, daemon=True).start()
threading.Thread(target=serve_hub, daemon=True).start()
while True:
    try:
        tick()
    except Exception as e:
        log("hata:", e)
    time.sleep(30)
