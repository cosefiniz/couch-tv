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
| [`tv-helper/`](tv-helper/) | A tiny TV app with no screen. With notification access it reads the playing app's position and length and seeks it (a draggable seek bar on the phone, YouTube tested), and hands out app icons. With accessibility on, the **red button flips between the last two channels** on Live TV (the TV understands `KEYCODE_LAST_CHANNEL`, its remote has no such button). |
| [`stick-guard/`](stick-guard/) | A small Python service next to the bridge: reopens ADB port 5555 after a power cut (once Wireless debugging is on, no pairing code), puts couch-tv in front after a boot, picks a shared profile on "Who's watching?" (TV+, Prime Video), a 03:00 sleep guard, and the app hub on port 5401 (download page, updates, notifications, media and seek for the phone). systemd units in `systemd/` wait for the clock (`time-sync.target`), for boxes with no RTC. |
| [`tv-browser/`](tv-browser/) | **TV Tarayıcı**, a remote-friendly web browser: desktop layout for old sites, a D-pad mouse cursor, zoom. |
| [`remote/`](remote/) | couch-tv's phone remote, changed: builds for older phone browsers, the Watch tab lists every app on the TV in the TV home's order (Live TV and the rest), and Now playing gets the seek bar. |

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
- **Stick nöbetçisi** (`stick-guard/`): elektrik kesintisinden sonra ADB portunu yeniden açar,
  açılışta couch-tv'yi öne getirir, "Kim izliyor?"da ortak profili seçer (TV+, Prime Video),
  gece 03:00 uyuyakalma koruması, 5401 portunda uygulama dağıtımı ve bildirimler.
- **TV Tarayıcı** (`tv-browser/`): eski siteler için masaüstü düzeni, kumandayla fare imleci.

Kurulum adımları yukarıda (İngilizce). Teşekkürler: couch-tv (Seagey), msm8916-mainline ve
OpenStick projeleri.
