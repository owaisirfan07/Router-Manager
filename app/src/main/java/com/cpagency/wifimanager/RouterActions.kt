package com.cpagency.wifimanager

/**
 * Settings you can CHANGE from the app.
 *
 * Each request copies exactly what the router's own admin page sends
 * (found by reading the page JavaScript of this HG8546M, firmware V3R017).
 * Every read gets fresh values from the router; every save re-reads the page
 * afterwards so the app can tell you if the router really applied it.
 *
 * Plain Kotlin (no Android classes) - call from a background thread.
 */
class RouterActions(private val c: RouterClient) {

    /* ------------------------------------------------------------ */
    /*  Reboot                                                        */
    /* ------------------------------------------------------------ */

    fun reboot() {
        try {
            c.submit(
                "html/ssmp/cfgfile/cfgfile.asp",
                "set.cgi?x=InternetGatewayDevice.X_HW_DEBUG.SSP.DBSave" +
                    "&y=InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard" +
                    "&RequestFile=html/ssmp/cfgfile/cfgfile.asp",
                emptyList()
            )
        } catch (e: java.io.IOException) {
            // the router often drops the connection as it goes down - that's fine
        }
    }

    /* ------------------------------------------------------------ */
    /*  Block / unblock a device (MAC filter, blacklist mode)        */
    /* ------------------------------------------------------------ */

    data class MacRule(val domain: String, val mac: String)
    data class MacFilter(val enabled: Boolean, val blacklist: Boolean, val rules: List<MacRule>)

    private val macPage = "html/bbsp/macfilter/macfilter.asp"

    fun macFilter(): MacFilter {
        val d = c.page(macPage)
        val rules = d.all("stMacFilter").map { MacRule(it["domain"], it["MACAddress"].ifBlank { it.fields.values.elementAtOrNull(1) ?: "" }) }
            .filter { it.mac.isNotBlank() }
        return MacFilter(d.v("enableFilter") == "1", d.v("Mode") != "1", rules)
    }

    fun isBlocked(mac: String, f: MacFilter = macFilter()) =
        f.enabled && f.blacklist && f.rules.any { it.mac.equals(mac, true) }

    fun blockDevice(mac: String) {
        var f = macFilter()
        if (!f.blacklist && f.rules.isNotEmpty()) {
            throw IllegalStateException("MAC filter is in WHITELIST mode on the router. Switch it to blacklist in the router page first.")
        }
        if (f.rules.size >= 8) throw IllegalStateException("The router allows max 8 blocked devices.")
        // turn the filter on in BLACKLIST mode (0) - never whitelist, that would block everything else
        if (!f.enabled || !f.blacklist) {
            c.submit(macPage, "set.cgi?x=InternetGatewayDevice.X_HW_Security&RequestFile=$macPage",
                listOf("x.MacFilterPolicy" to "0", "x.MacFilterRight" to "1"))
            f = macFilter()
        }
        if (f.rules.none { it.mac.equals(mac, true) }) {
            c.submit(macPage, "add.cgi?x=InternetGatewayDevice.X_HW_Security.MacFilter&RequestFile=$macPage",
                listOf("x.SourceMACAddress" to mac.uppercase()))
        }
        if (!isBlocked(mac)) throw IllegalStateException("Router did not save the block rule.")
    }

    fun unblockDevice(mac: String) {
        val rules = macFilter().rules.filter { it.mac.equals(mac, true) }
        if (rules.isEmpty()) return
        c.submit(macPage, "del.cgi?x=InternetGatewayDevice.X_HW_Security.MacFilter&RequestFile=$macPage",
            rules.map { it.domain to "" })
        if (isBlocked(mac)) throw IllegalStateException("Router did not remove the block rule.")
    }

    /* ------------------------------------------------------------ */
    /*  DHCP static IP (reservations)                                */
    /* ------------------------------------------------------------ */

    data class Reservation(val domain: String, val ip: String, val mac: String, val enabled: Boolean)

    private val staticPage = "html/bbsp/dhcpstatic/dhcpstatic.asp"

    fun reservations(): List<Reservation> =
        c.page(staticPage).all("stDhcp").map {
            Reservation(it["domain"], it["ipAddress"], it["macAddress"], it["Enable"] != "0")
        }.filter { it.mac.isNotBlank() }

    fun addReservation(mac: String, ip: String) {
        require(isMac(mac)) { "MAC address looks wrong (use AA:BB:CC:DD:EE:FF)" }
        require(isIp(ip)) { "IP address looks wrong" }
        val list = reservations()
        if (list.size >= 16) throw IllegalStateException("The router allows max 16 reservations.")
        if (list.any { it.ip == ip }) throw IllegalStateException("$ip is already reserved for another device.")
        list.filter { it.mac.equals(mac, true) }.forEach { deleteReservation(it.domain) }
        c.submit(staticPage,
            "add.cgi?x=InternetGatewayDevice.LANDevice.1.LANHostConfigManagement.DHCPStaticAddress&RequestFile=$staticPage",
            listOf("x.Yiaddr" to ip, "x.Chaddr" to mac.lowercase(), "x.Enable" to "1"))
        if (reservations().none { it.ip == ip }) throw IllegalStateException("Router did not save the reservation.")
    }

    fun deleteReservation(domain: String) {
        c.submit(staticPage,
            "del.cgi?x=InternetGatewayDevice.LANDevice.1.LANHostConfigManagement.DHCPStaticAddress&RequestFile=$staticPage",
            listOf(domain to ""))
    }

    /* ------------------------------------------------------------ */
    /*  LAN IP + DHCP server                                         */
    /* ------------------------------------------------------------ */

    data class Lan(
        val ip: String, val mask: String, val conflictDetect: String,
        val dhcpEnabled: Boolean, val start: String, val end: String, val leaseSeconds: Long,
        val dns1: String, val dns2: String, val l2relay: String, val option125: String
    )

    private val lanPage = "html/bbsp/dhcp/dhcp.asp"
    private val dhcpPage = "html/bbsp/dhcpservercfg/dhcp2.asp"

    fun lan(): Lan {
        val d = c.page(lanPage, dhcpPage)
        val host = d.first("stLanHostInfo")
        val main = d.all("dhcpmainst").firstOrNull { it["startip"].isNotBlank() } ?: d.first("dhcpmainst")
        val dns = (main?.get("DNSServers")?.ifBlank { main["MainDNS"] } ?: "").split(",").map { it.trim() }
        return Lan(
            ip = host?.get("ipaddr") ?: "",
            mask = host?.get("subnetmask") ?: "255.255.255.0",
            conflictDetect = host?.get("AddressConflictDetectionEnable")?.ifBlank { "1" } ?: "1",
            dhcpEnabled = main?.get("enable") != "0",
            start = main?.get("startip") ?: "",
            end = main?.get("endip") ?: "",
            leaseSeconds = main?.get("leasetime")?.toLongOrNull() ?: 86400,
            dns1 = dns.getOrElse(0) { "" },
            dns2 = dns.getOrElse(1) { "" },
            l2relay = main?.get("l2relayenable")?.ifBlank { "1" } ?: "1",
            option125 = main?.get("X_HW_Option125Enable")?.ifBlank { "0" } ?: "0"
        )
    }

    /** Changes the router's own LAN IP. Only allowed inside the same network (phones on WiFi would lose it otherwise). */
    fun saveLanIp(newIp: String, current: Lan) {
        require(isIp(newIp)) { "IP address looks wrong" }
        require(sameSubnet(newIp, current.ip, current.mask)) {
            "New IP must be in the same network as now (${network(current.ip, current.mask)}x). " +
                "Changing the whole network from a phone on WiFi would cut the phone off."
        }
        require(!inRange(newIp, current.start, current.end) || !current.dhcpEnabled) {
            "That IP is inside the DHCP range (${current.start} - ${current.end}). Pick one outside it."
        }
        c.submit(lanPage,
            "set.cgi?x=InternetGatewayDevice.LANDevice.1.LANHostConfigManagement.IPInterface.1&RequestFile=$lanPage",
            listOf(
                "x.IPInterfaceIPAddress" to newIp,
                "x.IPInterfaceSubnetMask" to current.mask,
                "x.X_HW_AddressConflictDetectionEnable" to current.conflictDetect
            ))
    }

    fun saveDhcp(new: Lan) {
        if (new.dhcpEnabled) {
            require(isIp(new.start) && isIp(new.end)) { "Start / end IP looks wrong" }
            require(sameSubnet(new.start, new.ip, new.mask) && sameSubnet(new.end, new.ip, new.mask)) {
                "Start and end must be in the router's network (${network(new.ip, new.mask)}x)"
            }
            require(ipToLong(new.start) <= ipToLong(new.end)) { "Start IP must be lower than end IP" }
            require(!inRange(new.ip, new.start, new.end)) { "The range can't include the router's own IP (${new.ip})" }
        }
        listOf(new.dns1, new.dns2).filter { it.isNotBlank() }.forEach { require(isIp(it)) { "DNS $it looks wrong" } }
        require(new.leaseSeconds in 60..(86400L * 30)) { "Lease time must be between 1 minute and 30 days" }

        val dns = listOf(new.dns1, new.dns2).filter { it.isNotBlank() }.joinToString(",")
        val p = arrayListOf(
            "z.DHCPServerEnable" to if (new.dhcpEnabled) "1" else "0",
            "z.X_HW_DHCPL2RelayEnable" to new.l2relay,
            "z.X_HW_Option125Enable" to new.option125,
            "z.X_HW_DNSList" to dns
        )
        if (new.dhcpEnabled) {
            p += "z.MinAddress" to new.start
            p += "z.MaxAddress" to new.end
        }
        p += "z.DHCPLeaseTime" to new.leaseSeconds.toString()
        c.submit(dhcpPage,
            "set.cgi?z=InternetGatewayDevice.LANDevice.1.LANHostConfigManagement&RequestFile=$dhcpPage", p)

        val after = lan()
        if (after.dhcpEnabled != new.dhcpEnabled || (new.dhcpEnabled && (after.start != new.start || after.end != new.end)))
            throw IllegalStateException("Router did not apply the DHCP change.")
    }

    /* ------------------------------------------------------------ */
    /*  WiFi advanced (radio)                                        */
    /* ------------------------------------------------------------ */

    data class Radio(
        val wlanDomain: String, val radioDomain: String,
        val channel: String, val width: String, val power: String, val standard: String, val country: String,
        val dtim: String, val beacon: String, val rts: String, val frag: String
    )

    private val advPage = "html/amp/wlanadv/WlanAdvance.asp"

    fun radio(): Radio {
        val d = c.page(advPage)
        val w = d.all("stWlanWifi").firstOrNull { it["SSID"].isNotBlank() || it["Name"].isNotBlank() }
            ?: throw IllegalStateException("Could not read WiFi radio settings")
        val adv = d.first("stWlanAdv")
        val radio = d.first("stWiFiRadio")
        return Radio(
            wlanDomain = w["domain"],
            radioDomain = radio?.get("domain")?.ifBlank { null } ?: "InternetGatewayDevice.LANDevice.1.WiFi.Radio.1",
            channel = w["Channel"].ifBlank { "0" },
            width = w["X_HW_HT20"].ifBlank { "0" },
            power = w["TransmitPower"].ifBlank { "100" },
            standard = w["X_HW_Standard"].ifBlank { "11bgn" },
            country = w["RegulatoryDomain"].ifBlank { "CN" },
            dtim = adv?.get("DtimPeriod")?.ifBlank { "1" } ?: "1",
            beacon = adv?.get("BeaconPeriod")?.ifBlank { "100" } ?: "100",
            rts = adv?.get("RTSThreshold")?.ifBlank { "2346" } ?: "2346",
            frag = adv?.get("FragThreshold")?.ifBlank { "2346" } ?: "2346"
        )
    }

    fun saveRadio(r: Radio) {
        val ch = r.channel.toIntOrNull() ?: -1
        require(ch in 0..13) { "Channel must be Auto or 1-13" }
        require(r.power in listOf("100", "80", "60", "40", "20")) { "Power must be 100/80/60/40/20 %" }
        require(r.standard in listOf("11b", "11g", "11n", "11bg", "11bgn")) { "Unknown WiFi standard" }
        val width = if (r.standard in listOf("11b", "11g", "11bg")) "1" else r.width // only 20 MHz for b/g
        require(r.dtim.toIntOrNull() in 1..255) { "DTIM must be 1-255" }
        require(r.beacon.toIntOrNull() in 20..1000) { "Beacon interval must be 20-1000" }
        require(r.rts.toIntOrNull() in 1..2346) { "RTS threshold must be 1-2346" }
        require(r.frag.toIntOrNull() in 256..2346) { "Fragment threshold must be 256-2346" }

        c.submit(advPage,
            "set.cgi?x=${r.wlanDomain}.X_HW_AdvanceConf&y=${r.wlanDomain}&r=${r.radioDomain}&RequestFile=$advPage",
            listOf(
                "y.Channel" to ch.toString(),
                "y.AutoChannelEnable" to if (ch == 0) "1" else "0",
                "y.X_HW_HT20" to width,
                "y.RegulatoryDomain" to r.country,
                "y.TransmitPower" to r.power,
                "y.X_HW_Standard" to r.standard,
                "x.DtimPeriod" to r.dtim,
                "x.BeaconPeriod" to r.beacon,
                "x.RTSThreshold" to r.rts,
                "x.FragThreshold" to r.frag
            ))
        val after = radio()
        if (after.channel != ch.toString() || after.power != r.power || after.standard != r.standard)
            throw IllegalStateException("Router did not apply the WiFi radio change.")
    }

    /* ------------------------------------------------------------ */
    /*  WAN (internet connection, PPPoE)                             */
    /* ------------------------------------------------------------ */

    data class Wan(
        val domain: String, val name: String, val status: String,
        val enable: Boolean, val username: String, val passwordHash: String,
        val vlan: String, val pri: String, val priPolicy: String, val defaultPri: String,
        val nat: Boolean, val natType: String, val mru: String, val lcpCheck: Boolean, val trigger: String,
        val serviceList: String, val exServiceList: String, val connType: String,
        val mcastVlan: String, val ipv6McastVlan: String, val ipv4: Boolean, val ipv6: Boolean,
        val dnsOverride: Boolean, val dnsServers: String, val bindPorts: String
    )

    private val wanPage = "html/bbsp/wan/wan.asp"

    fun wan(): Wan {
        val d = c.page("html/bbsp/common/wan_list_info.asp", "html/bbsp/common/wan_list.asp")
        val w = d.all("WanPPP").firstOrNull { it["ServiceList"].contains("INTERNET") } ?: d.first("WanPPP")
            ?: throw IllegalStateException("No PPPoE internet connection found. Only PPPoE WAN can be edited from the app.")
        val bind = try {
            c.page("html/bbsp/layer3/layer3.asp").all("stBindPhyPortInfo")
                .firstOrNull { it["_Domain"] == w["domain"] }?.get("_BindPhyPortInfo") ?: ""
        } catch (e: Exception) { "" }
        return Wan(
            domain = w["domain"], name = w["Name"], status = w["ConnectionStatus"],
            enable = w["Enable"] != "0", username = w["Username"], passwordHash = w["Password"],
            vlan = w["VlanId"].ifBlank { "0" }, pri = w["Pri8021"].ifBlank { "0" },
            priPolicy = w["PriPolicy"].ifBlank { "Specified" }, defaultPri = w["DefaultPri"].ifBlank { "0" },
            nat = w["NATEnable"] != "0", natType = w["X_HW_NatType"].ifBlank { "0" },
            mru = w["MaxMRUSize"].ifBlank { "1492" }, lcpCheck = w["LcpEchoReqCheck"] == "1",
            trigger = w["DialMode"].ifBlank { w["ConnectionTrigger"].ifBlank { "AlwaysOn" } },
            serviceList = w["ServiceList"], exServiceList = w["ExServiceList"],
            connType = w["Mode"].ifBlank { "IP_Routed" },
            mcastVlan = w["MultiVlanID"].ifBlank { "4294967295" },
            ipv6McastVlan = w["IPv6MultiCastVlan"].ifBlank { "-1" },
            ipv4 = w["IPv4Enable"] != "0", ipv6 = w["IPv6Enable"] == "1",
            dnsOverride = w["DNSOverrideAllowed"] == "1",
            dnsServers = if (w["DNSOverrideAllowed"] == "1") w["dnsstr"] else "",
            bindPorts = bind
        )
    }

    /** [newPassword] null/blank = keep the current one (the router's own page sends the stored hash back). */
    fun saveWan(new: Wan, newPassword: String?) {
        require(!new.ipv6) { "IPv6 WAN can't be edited from the app yet" }
        require(new.username.isNotBlank() && new.username.length <= 64) { "PPPoE username is required (max 64)" }
        if (!newPassword.isNullOrEmpty()) require(newPassword.all { it.code in 32..126 }) { "Password: use normal letters/numbers/symbols only" }
        val vlan = new.vlan.toIntOrNull() ?: -1
        require(vlan == 0 || vlan in 1..4094) { "VLAN ID must be 1-4094 (or 0 = off)" }
        require(new.pri.toIntOrNull() in 0..7) { "802.1p priority must be 0-7" }
        val mru = new.mru.toIntOrNull() ?: -1
        require(mru in 576..1540) { "MRU must be 576-1540 (normal: 1492)" }
        require(new.trigger in listOf("AlwaysOn", "Manual")) { "Dial mode must be Always on or Manual" }
        val dns = new.dnsServers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (new.dnsOverride) {
            require(dns.isNotEmpty() && dns.all { isIp(it) }) { "Enter 1 or 2 valid DNS IPs" }
        }

        val p = arrayListOf(
            "y.Enable" to if (new.enable) "1" else "0",
            "y.X_HW_IPv4Enable" to "1",
            "y.X_HW_IPv6Enable" to "0",
            "y.X_HW_IPv6MultiCastVLAN" to new.ipv6McastVlan,
            "y.X_HW_SERVICELIST" to new.serviceList,
            "y.X_HW_ExServiceList" to new.exServiceList,
            "y.X_HW_VLAN" to vlan.toString(),
            "y.X_HW_PRI" to if (vlan == 0) "0" else new.pri,
            "y.X_HW_PriPolicy" to if (vlan == 0) "Specified" else new.priPolicy,
            "y.X_HW_DefaultPri" to new.defaultPri,
            "y.ConnectionType" to new.connType,
            "y.X_HW_MultiCastVLAN" to new.mcastVlan,
            "y.NATEnabled" to if (new.nat) "1" else "0"
        )
        if (new.nat) p += "y.X_HW_NatType" to new.natType
        p += "y.Username" to new.username
        p += "y.Password" to (if (newPassword.isNullOrEmpty()) new.passwordHash else newPassword)
        p += "y.X_HW_LcpEchoReqCheck" to if (new.lcpCheck) "1" else "0"
        if (new.serviceList == "INTERNET") p += "y.ConnectionTrigger" to new.trigger
        p += "y.DNSEnabled" to "1"
        p += "y.MaxMRUSize" to mru.toString()
        p += "y.DNSOverrideAllowed" to if (new.dnsOverride) "1" else "0"
        p += "y.DNSServers" to if (new.dnsOverride) dns.joinToString(",") else ""
        p += "y.X_HW_BindPhyPortInfo" to new.bindPorts

        val d = new.domain
        c.submit(wanPage, "complex.cgi?y=$d&j=$d&r=$d&RequestFile=html/bbsp/wan/confirmwancfginfo.html", p)

        val after = wan()
        if (after.username != new.username || after.vlan != vlan.toString() || after.mru != mru.toString())
            throw IllegalStateException("Router did not apply the WAN change.")
    }

    /* ------------------------------------------------------------ */
    /*  helpers                                                      */
    /* ------------------------------------------------------------ */

    companion object {
        fun isIp(s: String) = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""").matches(s.trim())
        fun isMac(s: String) = Regex("""^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$""").matches(s.trim())
        fun ipToLong(ip: String) = ip.split(".").fold(0L) { acc, p -> acc * 256 + (p.toLongOrNull() ?: 0) }
        fun sameSubnet(a: String, b: String, mask: String): Boolean {
            if (!isIp(a) || !isIp(b) || !isIp(mask)) return false
            val m = ipToLong(mask)
            return ipToLong(a) and m == ipToLong(b) and m
        }
        fun inRange(ip: String, start: String, end: String) =
            isIp(ip) && isIp(start) && isIp(end) && ipToLong(ip) in ipToLong(start)..ipToLong(end)
        fun network(ip: String, mask: String): String {
            val parts = ip.split(".")
            return if (mask == "255.255.255.0" && parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}." else "$ip/$mask "
        }
    }
}
