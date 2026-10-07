# Router Manager – what's in the app

## New in this version
- **Bottom tabs:** Home · Devices · Settings · More. Router info pages moved to More > Router information (open one group at a time).
- **Home hero:** gradient panel with internet status, public IP and uptime, router CPU/RAM, an animated Internet → Router → Devices flow (mint = download, amber = upload, faster with more traffic; turns red when offline), live ↓/↑ Mbps and a 90-second traffic graph. Below: Fibre, Devices, WiFi and Health tiles + quick actions.
- **Devices tab:** online list (offline hidden behind a button), "This phone" and "Blocked" tags. Tap a device for:
  - **Internet access on/off** (router MAC filter, blacklist mode - can't block the phone you're using)
  - **Fixed IP** (DHCP reservation) add/remove
- **Settings tab (editable):** WiFi name/password, WiFi radio (channel, width, power, standard, DTIM/beacon/RTS/frag), Internet/WAN (PPPoE username/password, VLAN, 802.1p, MRU, NAT + type, LCP, dial mode, own DNS), DHCP & DNS (on/off, range, lease, DNS 1/2), Fixed IPs, Router IP (same network only), **Reboot router**, Log out.
- Every change is checked by reading the router again after saving - if the router didn't apply it, the app says so.
- Login details are remembered on the phone.

## Internet monitor (new)
- Runs in the background while this phone is on the home WiFi (Huawei or TP-Link). Checks every 15 s: router, your ISP, Google DNS (8.8.8.8), Cloudflare (1.1.1.1). Checks are pinned to WiFi, so they still work when Android switches to mobile data.
- Home card: Online + ms for Google / Cloudflare / ISP. When down: Router / ISP / Internet status, start time, duration, and the router's own reason (fibre LOS, ISP connection failed, or ISP connected but no internet).
- Alert notification when the internet goes down (after ~30 s) and when it comes back, with how long it was down.
- History by month (count + total time). Tap an outage to see details or delete it.
- If the app wasn't watching (phone away / killed), it checks the router's WAN uptime when it comes back and logs "reconnected at ..." without a guessed duration.

## Data usage (new)
- Cycle = your package: renews on the 3rd at 12:00 AM (changeable). At renewal the month goes to history automatically and counting starts from zero. Manual reset also saves to history. History entries can be deleted.
- Huawei WiFi: exact, from the router's own byte counters (all Huawei WiFi devices together - this firmware has no per-device count).
- TP-Link devices: per device, from the TP-Link's live speed per device added up every 15 s (estimate). READ ONLY - the app never changes anything on the TP-Link. Only counted while this phone is on the TP-Link WiFi (the TP-Link can't be reached from the Huawei side).
- Not counted: devices on Huawei LAN cable ports other than the TP-Link.

## Speed limit per device
The Huawei HG8546M (firmware V3R017) has **no per-device speed limit** in its firmware - QoS and "Intelligent Channel" only set priority, and the only rate limits are for the whole line (QoS total bandwidth) or a guest WiFi, which this router's firmware doesn't offer. So per-device speed limit is not in the app. Per-device **block** is available.

## Not editable yet
- Internet time limits (Parental Control) - the template page wasn't in the HAR.
- IPv6 WAN, firewall, port mapping, DMZ, routes, QoS - view only.

---


The app reads the same pages the router's web admin panel uses (Huawei HG8546M,
`192.168.100.1`), and shows them as simple cards. Every screen also has a
**"Show all raw data"** button with every field the router sent for that page,
so nothing is hidden. Only secret fields (password hashes, tokens) are not shown.

## ✅ Included in the app

### Internet Speed Test (Tools > Internet Speed Test, or the button on the login screen)
- Real download and upload speed in Mbps, live while testing (Cloudflare speed servers, 4 connections at once)
- Ping and jitter, your ISP, public IP and test server city
- Rating (Excellent / Good / Average / Slow) and the last 10 results saved on the phone
- Works without logging in to the router (also on mobile data - uses 50-300 MB per test)

### Home (dashboard)
- Internet: status, public IP, gateway, DNS, online time, connection name
- Fibre signal: Rx power (green/red against the -8 to -27 dBm range), Tx power, temperature, ONT status
- Connected devices: online count (WiFi / cable), total known devices
- Router: model, software version, CPU %, memory %, system time
- WiFi: SSID name and whether it's on/off
- Each card can be tapped to open its full screen

### Status
- **Connected Devices** – every device (online first): name, IP, MAC, WiFi/cable + port, online time, device type, DHCP lease left
- **One-Click Diagnosis** – last saved results: optical signal OK, ISP registration OK, internet status, WiFi password strength, LAN 1–4 cable connected

### System Info
- **Device Information** – model, description, serial, hardware/software version, vendor, release date, MAC, manufacture info, ONT ID/status, PON mode, CPU, memory, system time, "default admin password still set"
- **WAN Information** – per connection: status, last error, mode, IP, gateway, DNS, online time, PPPoE username, MAC, VLAN, 802.1p, service, NAT, IPv4/IPv6, PPPoE session ID, AC name, MRU, plus IPv6 WAN status
- **Optical Information** – Tx/Rx power, voltage, temperature, bias current, LOS, wavelengths, max distance, module vendor/serial/date, PON link time, PON packets sent/received, OLT side info
- **Service Provisioning Status** – ONT registration, OLT config, EMS config, ACS (TR-069) registration (same logic as the router page) + raw codes
- **Eth Port Information** – LAN 1–4 link up/down, plus whatever the router's Eth page returns
- **WLAN Information** – per SSID: name, on/off, broadcast, security, encryption, standard, channel, power, width, country, WMM, max devices, data sent/received, packets, errors, drops; router LAN/WiFi MAC; **WiFi clients** (name, signal RSSI, noise, SNR, Rx/Tx rate, connected time, mode, power-save); nearby WiFi networks
- **Smart WiFi Coverage** – connected extenders/APs (shows "none" if there aren't any)

### Advanced Configuration (view only)
- **WAN Configuration** – full connection settings (mode, service, dial mode, VLAN, priority, NAT, MRU, LCP check, idle settings, IGMP, IPv6 addressing, 6RD, DS-Lite, port binding)
- **LAN** – Layer 2/3 mode per port, LAN IPs, DHCP server (main + second pool, range, lease, DNS, options), DHCP static IPs, DHCPv6 server/prefix/RA/ULA/DNS, DHCPv6 static, DHCPv6 info
- **Security** – firewall level, DoS protections, IP filter, MAC filter, WiFi MAC filter, parental control (templates, blocked counts), device access control (telnet/web from LAN/WiFi), WAN access control
- **Route** – default route, static routes, RIP, VLAN binding, service routes, **routing table**, IPv6 default/static routes
- **Forward Rules** – DMZ, port mapping, port trigger
- **Application** – time/NTP, ALG, DDNS, UPnP, IGMP, intelligent channel, static DNS, DSCP-to-Pbit, QoS basic, QoS advance
- **WLAN** – basic (radio, SSID, security, WPS PIN/mode, isolation) **+ Edit WiFi button**, advanced (DTIM, beacon, RTS, frag, guard interval, beamforming, PMF, RSSI threshold…), automatic WiFi shutdown times, WiFi coverage management

### System Management / Maintenance (view only)
- TR-069 settings, web accounts + level, ONT serial number, current firmware version
- Last ping / traceroute result saved on the router, intelligent channel traffic statistics

### Editing (kept from v1.0)
- Main 2.4G WiFi: name, password, on/off, broadcast, WMM, WPS, security mode, max devices

### Other improvements
- Auto re-login when the router session times out
- One page failing doesn't break the whole screen (error is shown at the bottom)
- Back / Refresh / Logout buttons, phone back button works

## ❌ Not included (and why)

| Not in the app | Why |
|---|---|
| Changing any setting except main WiFi (firewall, DMZ, port mapping, routes, DHCP, QoS, etc.) | Each one has its own save format; a wrong save can lock you out. Can add the ones you actually use, one by one. |
| Running a new One-Click Diagnosis, ping or traceroute | These start tests on the router; app shows the last saved results only. |
| Firmware upgrade, config file backup/restore | Uploading files from a phone is risky; a failed upgrade can brick the router. |
| Reboot / factory reset | Not in the captured pages; easy to add next if you want it. |
| Changing admin password, ONT auth (LOID/password), TR-069 settings | Can break the ISP connection or lock you out. |
| WiFi password and PPPoE password values | The router itself never sends them (masked / hashed). |
| 5 GHz WiFi | This router (HG8546M) is 2.4 GHz only. |
| Long preset lists (64 port-mapping app presets, 51 IP filter presets, dropdown options) | They're the router's built-in templates, not your data. |
| VoIP / phone line pages | Not in this router's menu. |

## ⚠️ Not tested against the real router yet
These pages were **not in the HAR file**, so they were tested with sample data in
the standard Huawei format. Please check them on the real router:
- **Eth Port Information** (the router's own Eth page failed to load in the browser too) – LAN up/down part is tested
- **WLAN Information → WiFi clients and nearby networks** (`getassociateddeviceinfo.asp`, `getneighbourAPinfo.asp`)

If one of them shows wrong/empty values, send a new HAR with that page open and it can be fixed quickly.
