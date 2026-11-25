# Restoring the bridge on a new stick (or after a reflash)

What you need: a Debian box on the home Wi-Fi with `sudo`, and the newest backup
(`~/yedek/stick-*.tgz` on the old stick, or the copy `scripts/deploy-all.sh` keeps on the PC).
The backup holds everything that is not in git: couch-tv's `config.json` and `bridge/data/`
(the TV remote pairing keys and history), the guard's `config.json`, the ADB key the TV already
trusts (`~/.android/adbkey`, so the TV does not ask "Allow USB debugging?" again), the systemd units
and, when it was taken with sudo, the NetworkManager Wi-Fi profiles. **It contains secrets (the Wi-Fi
password, pairing keys): keep it out of git and off shared drives.**

1. System packages:
   `sudo apt install git adb python3-venv`
2. Code, on the same branch as before:
   ```sh
   git clone -b salon-tv https://github.com/cosefiniz/couch-tv.git ~/couch-tv
   cd ~/couch-tv && git remote add upstream https://github.com/Seagey/couch-tv.git
   python3 -m venv .venv && .venv/bin/pip install -r bridge/requirements.txt
   ```
   The guard's upstream check fetches `origin main`; on a clone of the fork, point it at Seagey:
   `git remote rename origin fork && git remote rename upstream origin`.
3. Unpack the backup over the home folder (it keeps the paths):
   `tar xzf stick-YYYYMMDD-HHMM.tgz -C ~` (as the user). If it holds `etc/` or `usr/` entries, copy those
   with sudo to `/etc/...` and `/usr/local/sbin/...`, or install the units fresh in step 5.
4. The phone remote's built page: copy `remote/dist` from the PC (`scripts/deploy-all.sh` does it).
5. Services:
   ```sh
   sudo cp stick-guard/systemd/couch-tv-bridge.service stick-guard/systemd/couch-tv-adb-guard.service \
           stick-guard/systemd/wifi-watchdog.service stick-guard/systemd/couch-tv-backup.* /etc/systemd/system/
   sudo install -m 755 stick-guard/wifi-watchdog.sh /usr/local/sbin/wifi-watchdog
   sudo mkdir -p /etc/systemd/system.conf.d
   sudo cp stick-guard/systemd/10-hw-watchdog.conf stick-guard/systemd/20-io-accounting.conf /etc/systemd/system.conf.d/
   sudo cp stick-guard/systemd/90-less-writes.apt.conf /etc/apt/apt.conf.d/90-less-writes
   sudo systemctl enable systemd-time-wait-sync.service
   sudo systemctl daemon-reexec
   sudo systemctl enable --now couch-tv-bridge couch-tv-adb-guard wifi-watchdog couch-tv-backup.timer
   ```
   Change `User=` and the `/home/user` paths in the units first if your user is not `user`.
6. Give the new stick the old one's address in the router (DHCP reservation), or change the address in
   the phone app's settings.
7. Check: `http://<stick>:5401/status.html` on a phone, or the app's System status page.
