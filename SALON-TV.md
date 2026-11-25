# Salon TV: couch-tv extras for a family Google TV

**English below · Türkçesi aşağıda**

This fork adds a few things on top of [couch-tv](https://github.com/Seagey/couch-tv) (option B,
with a bridge). They were built for one family's Grundig 55GQ750A (Google TV, Android TV 14,
MediaTek) with the bridge running on a **4G USB modem stick turned into a Debian mini PC**
([cosefiniz/4gmodem-customization](https://github.com/cosefiniz/4gmodem-customization)), but
nothing in them is tied to that TV or that stick.

| Folder | What it is |
|---|---|
| [`phone-app/`](phone-app/) | **Salon TV**, an Android app for the phone remote: couch-tv's web remote full screen, the phone's volume buttons change the TV's volume, a "TV power" quick settings tile, notifications from the stick, light mode for old phones (tested on a Samsung J7 Prime), and updates in place from the stick (`deploy.sh` builds and publishes). |
| [`tv-helper/`](tv-helper/) | A tiny TV app with no screen. With notification access it reads the playing app's position and length and seeks it (a draggable seek bar on the phone, YouTube tested), and hands out app icons. With WRITE_SECURE_SETTINGS granted it **switches Wireless debugging back on after every restart** (this TV forgets it), so the stick can always get back in. With accessibility on, the **red button flips between the last two channels** on Live TV (the TV understands `KEYCODE_LAST_CHANNEL`, its remote has no such button). |
| [`stick-guard/`](stick-guard/) | A small Python service next to the bridge: reopens ADB port 5555 within seconds of a restart (it listens for the TV's Wireless debugging announcement; no pairing code), puts couch-tv in front whenever the TV comes back with the Google TV home showing (never over live TV or an app), picks a shared profile on "Who's watching?" (TV+, Prime Video), a 03:00 sleep guard, and the app hub on port 5401 (download page, updates, notifications, media and seek for the phone). systemd units in `systemd/` wait for the clock (`time-sync.target`), for boxes with no RTC. Also a **Wi-Fi watchdog** (`wifi-watchdog.sh`): if the router stops answering it reconnects after 30 s, restarts the Wi-Fi services after 5 min and reboots after 15 min (at most once an hour), and `systemd/10-hw-watchdog.conf` arms the board's hardware watchdog for a hung kernel. A **status page** (`/status`, `/status.html`, and the phone app's System status) covers the stick, its services and the TV, with what to do when something is wrong; after a Google TV update the guard **puts back** what was switched off (couch-tv as the chosen home, the accessibility services, the helper's grants, ambient mode off, the screensaver). The hub answers the home network only and browsers only from the bridge's page. A **nightly backup** (`backup.sh`, `couch-tv-backup.timer`) keeps what git does not hold; [`RESTORE.md`](stick-guard/RESTORE.md) rebuilds a stick from it. Once a week it checks Seagey/couch-tv for new changes. |
| [`tv-browser/`](tv-browser/) | **TV Tarayıcı**, a remote-friendly web browser: desktop layout for old sites, a D-pad mouse cursor, zoom. |
| [`remote/`](remote/) | couch-tv's phone remote, changed: builds for older phone browsers, the Watch tab lists every app on the TV in the TV home's order (Live TV and the rest), and Now playing gets the seek bar. |
| [`scripts/deploy-all.sh`](scripts/deploy-all.sh), [`scripts/sync-upstream.sh`](scripts/sync-upstream.sh) | One command on the PC that publishes new commits, updates the stick and every installed copy, rebuilds and installs what changed (web remote, TV apps, phone app), copies the newest backup and prints the status; and one that merges couch-tv's own new changes into both branches. |
| [`scripts/staged-privacy-check.sh`](scripts/staged-privacy-check.sh) | Git hooks (`scripts/install-privacy-hooks.sh`) that check every commit and every push to a public remote for private addresses, e-mail addresses that are not GitHub noreply, local host names, keys and config files, and your own word list kept outside the repo. Only what leaves the computer is checked, so git-ignored local settings never block a commit. |

### Set it up
1. couch-tv itself, option B, as its [README](README.md) and [AGENTS.md](AGENTS.md) say.
2. Stick guard: `apt install adb`, copy `stick-guard/config.example.json` to `config.json` (the TV
   address falls back to couch-tv's `config.json`; `profile` empty switches profile picking off;
   `prime_profiles` are the avatar colours of your own Prime Video profile screen), then the two
   units in `stick-guard/systemd/`.
3. TV helper: build it (`tv-helper/build.cmd`, JDK 21 and the Android SDK in a tools folder, see
   the script), `adb install`, then on the TV give it notification access
   (`cmd notification allow_listener app.couchtv.helper/app.couchtv.helper.MediaListener`) and, for
   the red button, switch on **Kırmızı tuş: önceki kanal** under Settings, Accessibility.
4. Phone app: `phone-app/bridge.properties` with `host=<stick address>` (optional: without it the
   app searches the Wi-Fi), `signing.properties` with your own key, `deploy.env` with
   `STICK=user@<stick>`, then `phone-app/deploy.sh`. Phones open `http://<stick>:5401` once to
   install it; later versions come from inside the app.

Everything stays on the home network, like couch-tv's bridge: no password, so keep visitors on a
guest Wi-Fi.

### Credits
- [couch-tv](https://github.com/Seagey/couch-tv) by Seagey (MIT): the TV home, the bridge, the
  web remote. This fork only adds to it.
- The stick: [cosefiniz/4gmodem-customization](https://github.com/cosefiniz/4gmodem-customization),
  built on [msm8916-mainline](https://github.com/msm8916-mainline) (lk2nd, kernel) and the
  OpenStick builders.
- The extras here: [cosefiniz](https://github.com/cosefiniz), written with Claude Code.

---

## Türkçe

[couch-tv](https://github.com/Seagey/couch-tv)'nin (B seçeneği, köprülü kurulum) üstüne birkaç ek.
Bir ailenin Grundig 55GQ750A Google TV'si için, köprü **Debian'a çevrilmiş 4G USB modem stick**
üzerinde çalışırken yazıldı ([cosefiniz/4gmodem-customization](https://github.com/cosefiniz/4gmodem-customization)).

- **Salon TV telefon uygulaması** (`phone-app/`): kumanda, ses tuşlarıyla TV sesi, "TV gücü"
  hızlı ayar kutucuğu, stick'ten bildirimler, eski telefonlar için hafif mod, uygulama içinden
  güncelleme.
- **TV yardımcısı** (`tv-helper/`): telefonda sürüklenebilir zaman çubuğu (YouTube'da denendi),
  İzle sekmesinde TV'deki uygulamaların simgeleri, Canlı TV'de **kırmızı tuşla önceki kanal**.
- **Stick nöbetçisi** (`stick-guard/`): TV yeniden başlayınca ADB portunu saniyeler içinde açar
  (yardımcı Kablosuz hata ayıklamayı kendisi açar), Google TV paneli öndeyse couch-tv'yi getirir
  (canlı TV'ye ve açık uygulamalara dokunmaz), "Kim izliyor?"da ortak profili seçer (TV+, Prime Video),
  gece 03:00 uyuyakalma koruması, 5401 portunda uygulama dağıtımı ve bildirimler. **Wi-Fi bekçisi**
  (`wifi-watchdog.sh`): stick modeme ulaşamazsa 30 sn sonra yeniden bağlanır, 5 dk sonra Wi-Fi'yi yeniden
  başlatır, 15 dk sonra stick'i yeniden başlatır (saatte en fazla bir kez); donanım bekçisi donmaya karşı.
- **TV Tarayıcı** (`tv-browser/`): eski siteler için masaüstü düzeni, kumandayla fare imleci.

Kurulum adımları yukarıda (İngilizce). Teşekkürler: couch-tv (Seagey), msm8916-mainline ve
OpenStick projeleri.
