package com.cpagency.wifimanager

/*
 * Every screen in the app = one Section.
 * A Section lists which router pages to download (sources) and how to turn the
 * parsed data into simple cards (title + label/value rows).
 *
 * Pure Kotlin (no Android classes) so it can be tested on a normal JVM.
 */

data class Row(val label: String, val value: String)

data class Card(
    val title: String,
    val rows: List<Row> = emptyList(),
    /** Big headline value (used on the home dashboard) */
    val big: String? = null,
    /** true = green, false = red, null = neutral */
    val good: Boolean? = null,
    val note: String? = null,
    /** Section id to open when the card is tapped (home dashboard). */
    val link: String? = null
)

/** A router page to download. post = send as POST, token = include the page token. */
data class Src(val path: String, val post: Boolean = false, val token: Boolean = false)

class SectionResult(val cards: List<Card>, val raw: List<Card>, val errors: List<String>)

class Section(
    val id: String,
    val title: String,
    val group: String,
    val sources: List<Src>,
    /** Classes shown as normal cards (in this order). null = every data class found in the first source. */
    val classes: List<String>? = null,
    /** Extra hand-made cards shown before the generic ones. */
    val custom: ((PageData) -> List<Card>)? = null,
    /** Shown when the page has no entries (e.g. no port mappings set up yet). */
    val emptyText: String = "No entries set up on the router.",
    /** Small info line shown at the top. */
    val note: String? = null,
    /** Shows the "Edit WiFi settings" button. */
    val editWifi: Boolean = false,
    /** Optional: which fields to show for a class (in order). Everything else is still in raw data. */
    val fields: Map<String, List<String>> = emptyMap()
) {
    fun render(data: PageData): SectionResult {
        val cards = ArrayList<Card>()
        custom?.let { cards += it(data) }

        val classList = classes ?: autoClasses(data)
        for (cls in classList) {
            val seen = HashSet<Map<String, String>>()
            data.all(cls)
                .filterNot { Fmt.isPlaceholder(it) || Fmt.isEmptySlot(it) }
                .filter { seen.add(it.fields) } // drop exact duplicates
                .forEachIndexed { i, rec -> cards += Fmt.recordCard(rec, i, fields[cls]) }
        }
        if (cards.isEmpty()) cards += Card("Nothing to show", note = emptyText)

        val raw = data.allRecords()
            .filter { it.cls !in Fmt.templateClasses && !Fmt.isPlaceholder(it) }
            .mapIndexed { i, rec -> Fmt.rawCard(rec, i) }

        val errors = data.errors.map { (path, msg) -> "$path: $msg" }
        return SectionResult(cards, raw, errors)
    }

    private fun autoClasses(data: PageData): List<String> {
        val primary = sources.firstOrNull()?.path ?: return emptyList()
        val text = data.texts[primary] ?: return emptyList()
        return HwParser.parse(text).classes()
            .filter { it !in Fmt.templateClasses && it !in Fmt.contextClasses }
    }
}

/* ------------------------------------------------------------------ */
/*  Labels + value formatting                                          */
/* ------------------------------------------------------------------ */

object Fmt {

    /** JS helper/template classes that never hold real router data. */
    val templateClasses = setOf(
        "stTableClass", "stTableTileInfo", "stSpecParaArray", "CondetailInfo", "stInitOption",
        "stElementAttr", "stIndexMapping", "stSummaryInfo", "stTotalWlanAttr", "cVnew", "iVnew",
        "cV", "iVe", "ipFilterAppTempPortAdd", "PromptInfo", "stNoteInfo", "PortMappingPortList",
        "stPortMappingPortList", "AllWanInfoSt", "WanInfoInst", "stModifyUserInfoTemp"
    )

    /** Shared classes many pages include for context - hidden unless a section asks for them. */
    val contextClasses = setOf(
        "PolicyRouteItem", "TopoInfo", "TopoInfoClass", "stBindPhyPortInfo", "stipaddr",
        "dhcpcnst", "dhcpcnst1", "dhcpmainst", "condhcpst", "stLanDhcpEnable", "stDevInfo",
        "stHost", "Br0IPAddressItem", "stWlanInfo", "stRadio", "stTopoSsid", "stWlanEnable", "RaConfigInfoClass"
    )

    /** Fields never shown (internal paths and secrets). */
    private val hiddenKeys = setOf(
        "domain", "_domain", "password", "psk", "presharedkey", "key", "wepkey", "token",
        "x_hw_token", "ponpwd", "formliidlist", "bindfield"
    )

    private val classTitles = mapOf(
        "stDeviceInfo" to "Device", "ONTInfo" to "ONT", "OntStateInfo" to "ONT",
        "WanPPP" to "Internet connection (PPPoE)", "WanIP" to "Internet connection (IP)",
        "IPv6WanInfo" to "IPv6 WAN", "USERDevice" to "Device", "USERDeviceV6" to "Device (IPv6)",
        "DHCPInfo" to "DHCP lease", "stOpticInfo" to "Optical module", "stOLTOpticInfo" to "OLT optical",
        "stWlan" to "WiFi network (SSID)", "stWlanWifi" to "WiFi radio", "stPacketInfo" to "WiFi traffic",
        "stStats" to "WiFi errors", "stDeviceMac" to "MAC addresses", "stAssociatedDevice" to "WiFi client",
        "stNeighbourAP" to "Nearby WiFi network", "stLanHostInfo" to "LAN IP address",
        "dhcpmainst" to "DHCP server (main pool)", "dhcpcnst" to "DHCP server (second pool)",
        "madhcpst" to "DHCP address range", "SlaveDhcpInfo" to "Secondary DHCP",
        "stLayer3Enable" to "LAN port", "DhcpV6Server" to "DHCPv6 server",
        "Br0IPv6AddressClass" to "LAN IPv6 address", "Br0IPv6PrefixClass" to "LAN IPv6 prefix",
        "RaConfigInfoClass" to "Router advertisement (RA)", "UlaModeInfoClass" to "ULA mode",
        "UlaConfigInfoClass" to "ULA settings", "IPv6DNSConfigClass" to "IPv6 DNS",
        "stLanDhcp6Info" to "DHCPv6 address pool", "DhcpPoolClass" to "DHCPv6 pool",
        "DhcpHostClass" to "DHCPv6 clients", "stDos" to "DoS protection", "stPortFilter" to "IP filter settings",
        "stFilterIn" to "IP filter rule", "UserDevice" to "Device", "TemplatesListClass" to "Control template",
        "StatsListClass" to "Blocked packets", "stAclInfo" to "Device access control",
        "stDftRoute" to "Default route", "RouterInfoLanItem" to "RIP (dynamic route)",
        "BindInfoClass" to "LAN port VLAN binding", "BindInfoClassByWlan" to "WiFi VLAN binding",
        "RouteInfo" to "Route", "stPortMap" to "Port mapping", "stPortTrigger" to "Port trigger",
        "stTimeInfo" to "Time (SNTP)", "stAlg" to "ALG", "stDdns" to "DDNS", "stIGMPInfo" to "IGMP",
        "QosSmartEnableInfo" to "Intelligent channel", "stDnsInfo" to "DNS settings",
        "stLanDevice" to "WiFi features", "stWpsPin" to "WPS", "stWEPKey" to "WEP key",
        "stWiFiRadio" to "Radio", "stXHWGlobalConfig" to "Band steering", "stWlanAdv" to "Advanced radio",
        "stWifiCoverService" to "WiFi coverage", "stConfigurationByRadio" to "Coverage band",
        "stCWMP" to "TR-069 (ISP remote management)", "stModifyUserInfo" to "Web account",
        "stSSLWeb" to "HTTPS web login", "PingResultClass" to "Last ping test",
        "TracertResultClass" to "Last traceroute test", "QosSmartItem" to "Traffic rule",
        "GEInfo" to "LAN port", "stUpnpPortMapping" to "UPnP mapping", "stDuration" to "WiFi off period",
        "PONPackageInfo" to "PON packets", "stBindPhyPortInfo" to "Port binding",
        "EquipTestResultClass" to "Hardware self-test", "TDEPPPWanIPv6AddressClass" to "IPv6 addressing", "TDE_PPP_DelegationEnabledClass" to "IPv6 prefix delegation", "GetPppWan6RDTunnelInfo" to "6RD tunnel", "DsLiteInfo" to "DS-Lite", "PolicyRouteItem" to "Port binding (policy route)", "IPv6BindLanClass" to "IPv6 LAN binding", "UserDeviceV6" to "Device (IPv6)"
    )

    private val labels = mapOf(
        // device
        "SerialNumber" to "Serial number", "HardwareVersion" to "Hardware version",
        "SoftwareVersion" to "Software version", "ModelName" to "Model", "VendorID" to "Vendor ID",
        "ReleaseTime" to "Software release date", "Mac" to "MAC address", "ManufactureInfo" to "Manufacture info",
        "ONTID" to "ONT ID", "serialnumber" to "Serial number", "devtype" to "Device type",
        // wan
        "ConnectionTrigger" to "Connection trigger", "MACAddress" to "MAC address",
        "LastConnErr" to "Last connection error", "ConnectionStatus" to "Connection status",
        "Mode" to "Mode", "IPAddress" to "IP address", "Gateway" to "Gateway", "NATEnable" to "NAT",
        "X_HW_NatType" to "NAT type", "dnsstr" to "DNS servers", "Username" to "PPPoE username",
        "DialMode" to "Dial mode", "VlanId" to "VLAN ID", "MultiVlanID" to "Multicast VLAN",
        "Pri8021" to "802.1p priority", "LcpEchoReqCheck" to "LCP echo check", "ServiceList" to "Service",
        "Tr069Flag" to "TR-069 WAN", "IdleDisconnectTime" to "Idle disconnect time",
        "IPv4Enable" to "IPv4", "IPv6Enable" to "IPv6", "PriPolicy" to "Priority policy",
        "DefaultPri" to "Default priority", "MaxMRUSize" to "MRU", "PPPoEACName" to "PPPoE AC name",
        "X_HW_IdleDetectMode" to "Idle detect mode", "Uptime" to "Online for",
        "PPPoESessionID" to "PPPoE session ID", "DNSOverrideAllowed" to "DNS override allowed", "MacId" to "MAC ID", "IPv6MultiCastVlan" to "IPv6 multicast VLAN", "ConnectionControl" to "Connection control", "RemoteWanInfo" to "Remote WAN info", "X_HW_LowerLayers" to "Lower layers", "X_HW_IGMPEnable" to "IGMP", "Name" to "Name",
        "Enable" to "Enabled", "enable" to "Enabled", "Status" to "Status",
        "L2EncapType" to "Encapsulation", "Vlan" to "VLAN", "Pri" to "Priority",
        "DNSServers" to "DNS servers", "DefaultRouterAddress" to "Default router",
        "V6UpTime" to "IPv6 online for", "Type" to "Type", "PacketsSent" to "Packets sent",
        "PacketsReceived" to "Packets received",
        // lan devices
        "IpAddr" to "IP address", "MacAddr" to "MAC address", "Port" to "Port", "IpType" to "IP type",
        "DevType" to "Device type", "DevStatus" to "Status", "PortType" to "Connected by",
        "Time" to "Online for", "HostName" to "Device name", "IPv4Enabled" to "IPv4", "IPv6Enabled" to "IPv6",
        "DeviceType" to "Device type", "IP" to "IP address", "Scope" to "Scope", "PortID" to "Port / SSID",
        // optical
        "transOpticPower" to "Tx optical power", "revOpticPower" to "Rx optical power",
        "voltage" to "Supply voltage", "temperature" to "Temperature", "bias" to "Bias current",
        "rfRxPower" to "RF Rx power", "rfOutputPower" to "RF output power", "VendorName" to "Module vendor",
        "VendorSN" to "Module serial", "DateCode" to "Module date code", "TxWaveLength" to "Tx wavelength",
        "RxWaveLength" to "Rx wavelength", "MaxTxDistance" to "Max distance", "LosStatus" to "Signal lost (LOS)",
        "BudgetClass" to "Budget class", "TxPower" to "OLT Tx power", "PONIdentifier" to "PON ID",
        // wlan
        "ssid" to "WiFi name (SSID)", "SSID" to "WiFi name (SSID)", "name" to "Interface",
        "BeaconType" to "Security mode", "wlHide" to "SSID broadcast", "DeviceNum" to "Max devices",
        "wmmEnable" to "WMM", "mode" to "Mode", "X_HW_Standard" to "WiFi standard",
        "channel" to "Channel", "Channel" to "Channel", "power" to "Transmit power",
        "TransmitPower" to "Transmit power", "Country" to "Country / region",
        "RegulatoryDomain" to "Country / region", "AutoChannelEnable" to "Auto channel",
        "channelWidth" to "Channel width", "X_HW_HT20" to "Channel width",
        "X_HW_WPAand11iEncryptionModes" to "Encryption", "IEEE11iEncryptionModes" to "WPA2 encryption",
        "WPAEncryptionModes" to "WPA encryption", "BasicEncryptionModes" to "WEP encryption",
        "X_HW_ServiceEnable" to "Service enabled", "SSIDAdvertisementEnabled" to "SSID broadcast",
        "totalBytesSent" to "Data sent", "totalBytesReceived" to "Data received",
        "totalPacketsSent" to "Packets sent", "totalPacketsReceived" to "Packets received",
        "errorsSent" to "Send errors", "errorsReceived" to "Receive errors",
        "discardPacketsSent" to "Dropped (sent)", "discardPacketsReceived" to "Dropped (received)",
        "LanMac" to "LAN MAC", "WLanMac" to "WiFi MAC", "AssociatedDeviceMACAddress" to "MAC address",
        "X_HW_Uptime" to "Connected for", "X_HW_RxRate" to "Rx rate", "X_HW_TxRate" to "Tx rate",
        "X_HW_RSSI" to "Signal (RSSI)", "X_HW_Noise" to "Noise", "X_HW_SNR" to "SNR",
        "X_HW_SingalQuality" to "Signal quality", "X_HW_WorkingMode" to "Mode",
        "X_HW_WMMStatus" to "WMM", "X_HW_PSMode" to "Power saving", "BSSID" to "BSSID",
        "NetworkType" to "Network type", "RSSI" to "Signal (RSSI)", "Noise" to "Noise",
        "DtimPeriod" to "DTIM period", "BeaconPeriod" to "Beacon interval", "Security" to "Security",
        "Standard" to "Standard", "MaxBitRate" to "Max bit rate", "RTSThreshold" to "RTS threshold",
        "FragThreshold" to "Fragment threshold", "GuardInterval" to "Guard interval",
        "BandSteeringPolicy" to "Band steering", "X_HW_ConfigMethod" to "WPS mode",
        "DevicePassword" to "WPS PIN", "X_HW_PinGenerator" to "PIN generator", "WlanCfg" to "WiFi config",
        "Wps2" to "WPS 2.0", "X_HW_WorkMode" to "Work mode", "X_IEEE80211wEnabled" to "802.11w (PMF)",
        "X_TxBFEnabled" to "Beamforming", "X_HW_MCS" to "MCS", "X_HW_RSSIThreshold" to "RSSI threshold",
        "X_HW_RSSIThresholdEnable" to "RSSI threshold enabled", "X_HW_AutoChannelPeriodically" to "Re-pick channel periodically",
        "X_SCSEnables" to "SCS", "X_OCCACEnables" to "OCAC", "ChannelPlus" to "Channel plus",
        "OperatingFrequencyBand" to "Band", "X_HW_RFBand" to "Band", "RFBand" to "Band",
        "AutoExtended" to "Auto extend", "AutoSwitchAP" to "Auto switch AP",
        "ForcedSwitchThrehold" to "Forced switch threshold", "ConditionalSwitchThrehold" to "Conditional switch threshold",
        "SyncWifiSwitch" to "Sync WiFi on/off", "IspExtended" to "ISP extended",
        "AutoExtendedPolicy" to "Auto extend policy", "AutoExtendedSSIDIndex" to "Extended SSID index",
        "StartTime" to "From", "EndTime" to "To", "RepeatDay" to "Days",
        // lan / dhcp
        "ipaddr" to "IP address", "subnetmask" to "Subnet mask", "SubnetMask" to "Subnet mask",
        "startip" to "Start IP", "endip" to "End IP", "ipstart" to "Start IP", "ipend" to "End IP",
        "gateway" to "Gateway", "leasetime" to "Lease time", "LeaseTime" to "Lease time",
        "dhcpStart" to "Start IP", "dhcpEnd" to "End IP", "dhcpleasetime" to "Lease time",
        "MainDNS" to "Primary DNS", "SlaDNS" to "Secondary DNS", "l2relayenable" to "L2 relay",
        "X_HW_Option125Enable" to "Option 125", "OptionEnable" to "DHCP options",
        "DHCPServerEnable" to "DHCP server", "lay3enable" to "Layer 3 mode",
        "AddressConflictDetectionEnable" to "IP conflict detection", "option60" to "Option 60",
        "NormalUserEnable" to "Normal user can edit", "DHCPv6LeaseTime" to "Lease time",
        "Alias" to "Alias", "IPv6Address" to "IPv6 address", "ChildPrefixMask" to "Child prefix mask",
        "Prefix" to "Prefix", "PreferredLifeTime" to "Preferred lifetime", "ValidLifeTime" to "Valid lifetime",
        "PreferredLifetime" to "Preferred lifetime", "ValidLifetime" to "Valid lifetime",
        "ManagedFlag" to "Managed flag (M)", "OtherConfigFlag" to "Other config flag (O)", "MTU" to "MTU",
        "ULAmode" to "ULA mode", "ULAPrefix" to "ULA prefix", "ULAPrefixLen" to "ULA prefix length",
        "IPv6DNSConfigType" to "IPv6 DNS source", "IPv6DNSServers" to "IPv6 DNS servers",
        "MinAddress" to "First address", "MaxAddress" to "Last address", "HostNumberOfEntries" to "Clients",
        // security
        "SynFloodEn" to "SYN flood protection", "IcmpEchoReplyEn" to "ICMP echo reply protection",
        "IcmpRedirectEn" to "ICMP redirect protection", "LandEn" to "LAND attack protection",
        "SmurfEn" to "Smurf attack protection", "WinnukeEn" to "WinNuke protection",
        "PingSweepEn" to "Ping sweep protection", "ipFilterInRight" to "IP filter enabled",
        "ipFilterInPolicy" to "IP filter policy", "FirewallLevel" to "Firewall level",
        "Protocol" to "Protocol", "Direction" to "Direction", "Action" to "Action",
        "TelnetLanEnable" to "Telnet from LAN", "HTTPWifiEnable" to "Web admin from WiFi",
        "TELNETWifiEnable" to "Telnet from WiFi", "UrlFilterPolicy" to "URL filter policy",
        "UrlFilterRight" to "URL filter", "DurationPolicy" to "Time limit policy", "DurationRight" to "Time limit",
        // route
        "autoenable" to "Automatic", "DestIPAddress" to "Destination", "DestSubnetMask" to "Subnet mask",
        "GatewayIPAddress" to "Gateway", "Interface" to "Interface", "_RouterProtocol" to "RIP version",
        "_RouterProtocolMode" to "RIP mode", "MultiCastVlanAct" to "Multicast VLAN action",
        "MultiCastVlan" to "Multicast VLAN", "ProtMapEnabled" to "Port mapping enabled",
        // application
        "ntp1" to "NTP server 1", "ntp2" to "NTP server 2", "ntp3" to "NTP server 3", "ntp4" to "NTP server 4",
        "ZoneName" to "Time zone", "SynInterval" to "Sync interval", "WanName" to "WAN", "DstUsed" to "Daylight saving",
        "StartDate" to "DST start", "EndDate" to "DST end", "FTPEnable" to "FTP", "TFTPEnable" to "TFTP",
        "H323Enable" to "H.323", "PptpEnable" to "PPTP", "L2TPForward" to "L2TP", "IPSecForward" to "IPSec",
        "SipEnable" to "SIP", "RTSPEnable" to "RTSP", "RTCPEnable" to "RTCP", "RTCPPort" to "RTCP port",
        "Provider" to "Provider", "HostName" to "Host name", "IGMPEnable" to "IGMP",
        "ProxyEnable" to "IGMP proxy", "SnoopingEnable" to "IGMP snooping", "IGMPVersion" to "IGMP version",
        "Robustness" to "Robustness", "GenQueryInterval" to "Query interval (s)", "GenResponseTime" to "Query response time (1/10 s)", "SpQueryNumber" to "Last member query count", "SpQueryInterval" to "Last member query interval", "SpResponseTime" to "Last member response time", "RemarkIPPrecedence" to "Remark IP precedence", "RemarkPri" to "Remark priority", "STBNumber" to "Set-top boxes", "BridgeWanProxyEnable" to "Bridge WAN proxy", "PPPoEWanSnoopingMode" to "PPPoE WAN snooping mode", "PPPoEWanProxyMode" to "PPPoE WAN proxy mode", "StartDate_EX" to "DST start rule", "EndDate_EX" to "DST end rule", "DSCP" to "DSCP", "IsolationEnable" to "Client isolation", "KeyIndex" to "WEP key index", "EncryptionLevel" to "WEP key length", "WPARekey" to "Group key rekey (s)",
        "_QosSmartEnableValue" to "Intelligent channel", "policy" to "Policy", "type" to "Record type",
        // system
        "EnableCWMP" to "TR-069", "PeriodicInformEnable" to "Periodic inform",
        "PeriodicInformInterval" to "Inform interval", "X_HW_EnableCertificate" to "Certificate",
        "X_HW_DSCP" to "DSCP", "X_HW_CheckPasswordComplex" to "Password complexity check",
        "URL" to "ACS URL", "ConnectionRequestUsername" to "Connection request user",
        "UserName" to "Username", "UserLevel" to "Account level", "ModifyPasswordFlag" to "Password changed",
        // maintenance
        "DiagnosticsState" to "State", "NumberOfRepetitions" to "Repetitions", "Timeout" to "Timeout (ms)",
        "DataBlockSize" to "Packet size", "FailureCount" to "Failed", "SuccessCount" to "Succeeded",
        "MinimumResponseTime" to "Min time (ms)", "MaximumResponseTime" to "Max time (ms)",
        "AverageResponseTime" to "Avg time (ms)", "NumberOfTries" to "Tries", "MaxHopCount" to "Max hops",
        "ResponseTime" to "Response time (ms)", "RouteHopsNumberOfEntries" to "Hops",
        "EthNum" to "Ethernet ports", "SSIDNum" to "SSIDs", "SsidNum" to "SSIDs"
    )

    private val secondsKeys = setOf(
        "uptime", "leasetime", "dhcpleasetime", "linktime", "syninterval", "periodicinforminterval",
        "idledisconnecttime", "dhcpv6leasetime", "v6uptime", "remaintime", "validlifetime",
        "preferredlifetime", "x_hw_uptime"
    )

    /** Labels that only apply to one class ("Class.field"). */
    private val classLabels = mapOf(
        "stWlanWifi.mode" to "WiFi standard",
        "stWlanWifi.name" to "Interface",
        "USERDevice.Port" to "Port / SSID",
        "stLayer3Enable.lay3enable" to "Layer 3 mode"
    )

    fun label(key: String, cls: String = ""): String = classLabels["$cls.$key"] ?: labels[key] ?: pretty(key)

    /** Words written in capitals when they appear in an auto-made label. */
    private val upperWords = setOf(
        "ip", "ipv4", "ipv6", "wan", "lan", "dns", "vlan", "mac", "stb", "dscp", "igmp", "ssid", "dhcp", "dhcpv6",
        "nat", "ont", "olt", "pon", "url", "id", "rf", "ap", "wps", "wmm", "qos", "ppp", "pppoe", "tcp", "udp",
        "dmz", "ddns", "upnp", "alg", "acl", "ntp", "dst", "mtu", "mru", "lcp", "sn", "ssl", "rip", "ra", "ula",
        "wapi", "wpa", "wep", "uapsd", "rssi", "snr", "tr069", "cwmp", "ems", "acs", "http", "https"
    )

    /** "X_HW_SomethingEnable" -> "Something enable" */
    fun pretty(key: String): String {
        var k = key.trimStart('_').removePrefix("X_HW_").removePrefix("X_").replace("11i", "WPA2")
        k = k.replace('_', ' ')
        k = k.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        k = k.replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        var low = " " + k.trim().lowercase().replace(Regex("\\s+"), " ") + " "
        // glue back tokens the camelCase split breaks apart
        for ((a, b) in listOf(" i pv6 " to " ipv6 ", " i pv4 " to " ipv4 ", " dhc pv6 " to " dhcpv6 ",
            " pp po e " to " pppoe ", " ppp o e " to " pppoe ", " wi fi " to " wifi ")) low = low.replace(a, b)
        val words = low.trim().split(" ").filter { it.isNotEmpty() }.map { w ->
            when (w) {
                "ipv4" -> "IPv4"; "ipv6" -> "IPv6"; "dhcpv6" -> "DHCPv6"; "pppoe" -> "PPPoE"; "wifi" -> "WiFi"
                in upperWords -> w.uppercase()
                else -> w
            }
        }
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    fun classTitle(cls: String): String = classTitles[cls] ?: pretty(cls.removePrefix("st"))

    fun hidden(key: String) = key.lowercase() in hiddenKeys || key.lowercase().contains("password") && key != "DevicePassword"

    /** All-zero / empty records are JS placeholders, not data. */
    fun isPlaceholder(rec: HwRecord): Boolean {
        val values = rec.fields.filterKeys { !hidden(it) }.values
        return values.isEmpty() || values.all { it.isBlank() || it == "0" || it == "--" }
    }

    /** WiFi records with an SSID field that is blank are unused slots (e.g. SSID2-4 not set up). */
    fun isEmptySlot(rec: HwRecord): Boolean {
        val k = listOf("ssid", "SSID").firstOrNull { rec.has(it) } ?: return false
        return rec[k].isBlank()
    }

    private val onOffKeyRegex = Regex("(?i)(enable|enabled|enables|en|switch|right|forward)$|^(?i)enable")

    private val onOffKeys = setOf(
        "wlhide", "wmmenable", "lay3enable", "wps2", "l2relayenable", "dstused", "autoenable", "losstatus",
        "x_hw_wmmstatus", "x_hw_psmode", "autoextended", "ispextended", "autoswitchap",
        "x_hw_autochannelperiodically", "managedflag", "otherconfigflag", "lcpechoreqcheck", "tr069flag",
        "dnsoverrideallowed", "natenable", "x_hw_checkpasswordcomplex", "wlancfg", "x_hw_dhcpv6foraddress",
        "x_hw_e8c_ipv6prefixdelegationenabled", "modifypasswordflag", "ipv4enabled", "ipv6enabled"
    )

    fun value(key: String, raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        val k = key.lowercase()
        if (v == "4294967295") return "Not set"
        if ((k.contains("dns") || k.contains("ntp")) && v.contains(",")) return v.replace(Regex(",(?=\\S)"), ", ")

        // on / off flags
        if ((onOffKeyRegex.containsMatchIn(key) || k in onOffKeys) && (v == "0" || v == "1")) {
            if (k == "modifypasswordflag") return if (v == "1") "Yes" else "No"
            if (k == "losstatus") return if (v == "1") "Yes - fibre signal lost" else "No"
            return if (v == "1") "On" else "Off"
        }
        if (v == "true") return "On"
        if (v == "false") return "Off"

        if (k in secondsKeys) v.toLongOrNull()?.let { return duration(it) }

        when (k) {
            "totalbytessent", "totalbytesreceived" -> v.toLongOrNull()?.let { return bytes(it) }
            "transopticpower", "revopticpower", "txpower", "rfrxpower", "rfoutputpower" ->
                return if (v == "--") "--" else "$v dBm"
            "voltage" -> v.toDoubleOrNull()?.let { return String.format("%.2f V", it / 1000) }
            "temperature" -> return "$v °C"
            "bias" -> return "$v mA"
            "txwavelength", "rxwavelength" -> return "$v nm"
            "maxtxdistance" -> return "$v km"
            "beacontype" -> return securityName(v)
            "x_hw_standard", "mode" -> if (v.startsWith("11")) return "802.$v"
            "channel" -> if (v == "0") return "Auto"
            "power", "transmitpower" -> return "$v%"
            "channelwidth", "x_hw_ht20" -> return when (v) { "0" -> "Auto 20/40 MHz"; "1" -> "20 MHz"; "2" -> "40 MHz"; else -> v }
            "x_hw_rxrate", "x_hw_txrate" -> return "$v Mbps"
            "x_hw_rssi", "x_hw_noise", "rssi", "noise" -> return "$v dBm"
            "x_hw_snr" -> return "$v dB"
            "userlevel" -> return when (v) { "0" -> "Administrator"; "1" -> "Normal user"; else -> v }
        }
        if (v.endsWith("Encryption")) return encryptionName(v)
        if (v.endsWith("Authentication")) return v.removeSuffix("Authentication")
        if (v == "IP_Routed") return "Route"
        if (v == "IP_Bridged") return "Bridge"
        return v
    }

    fun securityName(v: String) = when (v) {
        "WPA" -> "WPA-PSK"
        "11i" -> "WPA2-PSK"
        "WPAand11i" -> "WPA/WPA2-PSK"
        "Basic" -> "WEP"
        "None" -> "Open (no password)"
        else -> v
    }

    fun encryptionName(v: String) = when (v) {
        "AESEncryption" -> "AES"
        "TKIPEncryption" -> "TKIP"
        "TKIPandAESEncryption" -> "TKIP + AES"
        "WEPEncryption" -> "WEP"
        else -> v.removeSuffix("Encryption")
    }

    fun duration(sec: Long): String {
        if (sec <= 0) return "0"
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (h > 0 || d > 0) append("${h}h ")
            if (d == 0L) append("${m}m ")
            if (d == 0L && h == 0L) append("${s}s")
        }.trim()
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format("%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    /** Builds label/value rows. keys = which fields (null = all non-hidden). Empty values are skipped. */
    fun rows(rec: HwRecord, keys: List<String>? = null): List<Row> {
        val list = keys ?: rec.fields.keys.toList()
        return list.filter { !hidden(it) && rec.has(it) }
            .mapNotNull { k -> value(k, rec[k]).takeIf { it.isNotEmpty() }?.let { Row(label(k, rec.cls), it) } }
    }

    private val titleFields = listOf("HostName", "ssid", "SSID", "UserName", "Name", "MacAddr")

    fun recordCard(rec: HwRecord, index: Int, keys: List<String>? = null): Card {
        val nameField = titleFields.firstOrNull { rec.fields[it]?.isNotBlank() == true }
        val base = classTitle(rec.cls)
        val title = if (nameField != null) "$base: ${rec[nameField]}" else if (index > 0) "$base ${index + 1}" else base
        return Card(title, rows(rec, keys))
    }

    fun rawCard(rec: HwRecord, index: Int): Card {
        val rows = rec.fields.filter { !hidden(it.key) && it.value.isNotBlank() }
            .map { Row(it.key, it.value) }
        return Card("${rec.cls} #${index + 1}", rows)
    }

    fun r(label: String, value: String?) = Row(label, if (value.isNullOrBlank()) "--" else value)
}

/* ------------------------------------------------------------------ */
/*  Hand-made cards for the most important screens                     */
/* ------------------------------------------------------------------ */

object Custom {

    private fun wanList(d: PageData): List<HwRecord> = d.all("WanPPP") + d.all("WanIP")

    fun internetWan(d: PageData): HwRecord? {
        val list = wanList(d)
        return list.firstOrNull { it["ServiceList"].contains("INTERNET") && it["ConnectionStatus"] == "Connected" }
            ?: list.firstOrNull { it["ServiceList"].contains("INTERNET") }
            ?: list.firstOrNull()
    }

    private fun opticRxGood(rx: String): Boolean? {
        val v = rx.trim().toDoubleOrNull() ?: return null
        return v in -27.0..-8.0
    }

    fun devices(d: PageData): List<HwRecord> =
        d.all("USERDevice").filter { it["MacAddr"].isNotBlank() }

    fun deviceCard(dev: HwRecord): Card {
        val name = dev["HostName"].ifBlank { dev["DevType"].ifBlank { "Unknown device" } }
        val online = dev["DevStatus"].equals("Online", true)
        val via = if (dev["PortType"] == "WIFI") "WiFi (${dev["Port"]})" else "Cable (${dev["Port"]})"
        return Card(
            name,
            listOf(
                Fmt.r("Status", dev["DevStatus"]),
                Fmt.r("IP address", dev["IpAddr"]),
                Fmt.r("MAC address", dev["MacAddr"]),
                Fmt.r("Connected by", via),
                Fmt.r("Online for (h:m)", dev["Time"]),
                Fmt.r("IP type", dev["IpType"]),
                Fmt.r("Device type", dev["DevType"])
            ),
            good = online
        )
    }

    fun home(d: PageData): List<Card> {
        val cards = ArrayList<Card>()

        val wan = internetWan(d)
        if (wan != null) {
            val connected = wan["ConnectionStatus"] == "Connected"
            cards += Card(
                "Internet",
                listOf(
                    Fmt.r("Public IP", wan["IPAddress"]),
                    Fmt.r("Gateway", wan["Gateway"]),
                    Fmt.r("DNS", Fmt.value("dnsstr", wan["dnsstr"])),
                    Fmt.r("Online for", Fmt.value("Uptime", wan["Uptime"])),
                    Fmt.r("Connection", wan["Name"])
                ),
                big = wan["ConnectionStatus"].ifBlank { "Unknown" },
                good = connected,
                link = "waninfo"
            )
        } else {
            cards += Card("Internet", big = "No WAN found", good = false, link = "waninfo")
        }

        val optic = d.first("stOpticInfo")
        val ont = d.first("ONTInfo") ?: d.first("OntStateInfo")
        if (optic != null) {
            val rx = optic["revOpticPower"].trim()
            cards += Card(
                "Fibre signal",
                listOf(
                    Fmt.r("Rx power", Fmt.value("revOpticPower", rx)),
                    Fmt.r("Tx power", Fmt.value("transOpticPower", optic["transOpticPower"])),
                    Fmt.r("Temperature", Fmt.value("temperature", optic["temperature"])),
                    Fmt.r("ONT status", ont?.get("Status")),
                    Fmt.r("Good range", "-8 to -27 dBm")
                ),
                big = Fmt.value("revOpticPower", rx),
                good = opticRxGood(rx),
                link = "opticinfo"
            )
        }

        val devs = devices(d)
        val online = devs.filter { it["DevStatus"].equals("Online", true) }
        cards += Card(
            "Connected devices",
            listOf(
                Fmt.r("On WiFi", online.count { it["PortType"] == "WIFI" }.toString()),
                Fmt.r("On cable", online.count { it["PortType"] != "WIFI" }.toString()),
                Fmt.r("Known devices (incl. offline)", devs.size.toString())
            ),
            big = "${online.size} online",
            link = "devices"
        )

        val info = d.first("stDeviceInfo")
        cards += Card(
            "Router",
            listOf(
                Fmt.r("Model", info?.get("ModelName")),
                Fmt.r("Software", info?.get("SoftwareVersion")),
                Fmt.r("CPU usage", d.v("cpuUsed")),
                Fmt.r("Memory usage", d.v("memUsed")),
                Fmt.r("System time", d.v("systemdsttime"))
            ),
            link = "deviceinfo"
        )

        val ssids = d.all("stWlanInfo").filter { it["ssid"].isNotBlank() }.distinctBy { it["domain"] }
        if (ssids.isNotEmpty()) {
            cards += Card(
                "WiFi",
                ssids.map { Row("${it["ssid"]} (${it["X_HW_RFBand"].ifBlank { "2.4GHz" }})", if (it["enable"] == "1") "On" else "Off") },
                link = "wlaninfo"
            )
        }
        return cards
    }

    fun allDevices(d: PageData): List<Card> {
        val devs = devices(d).sortedByDescending { it["DevStatus"].equals("Online", true) }
        val leases = d.all("DHCPInfo").associateBy { it["MACAddress"].ifBlank { it.fields.values.elementAtOrNull(3) ?: "" }.lowercase() }
        return devs.map { dev ->
            val card = deviceCard(dev)
            val lease = leases[dev["MacAddr"].lowercase()]
            val extra = lease?.let { l ->
                l.fields.entries.firstOrNull { it.key.contains("Lease", true) || it.key.contains("remain", true) }
                    ?.value?.toLongOrNull()?.let { Row("DHCP lease left", Fmt.duration(it)) }
            }
            if (extra != null) card.copy(rows = card.rows + extra) else card
        }
    }

    fun diagnosis(d: PageData): List<Card> {
        val cards = ArrayList<Card>()
        val pwdWeak = d.v("flag") == "1"
        val opticOk = d.v("opticInfo").let { it.isNotBlank() && it != "--" }
        val mode = d.v("ontPonMode").uppercase()
        val regOk = when (mode) {
            "GPON" -> d.v("gponStatus").equals("O5", true)
            "EPON" -> d.v("eponStatus").equals("ONLINE", true)
            else -> false
        }
        val wan = internetWan(d)
        cards += Card(
            "Health check",
            listOf(
                Row("Optical signal", if (opticOk) "OK (${d.v("opticInfo")} dBm)" else "Not OK"),
                Row("Registration with ISP ($mode)", if (regOk) "OK" else "Not OK"),
                Row("Internet connection", wan?.get("ConnectionStatus")?.ifBlank { "--" } ?: "No WAN"),
                Row("WiFi password strength", if (pwdWeak) "Weak - change it" else "Strong")
            ),
            good = opticOk && regOk && wan?.get("ConnectionStatus") == "Connected"
        )
        val ports = d.all("GEInfo")
        if (ports.isNotEmpty()) {
            cards += Card("LAN ports", ports.mapIndexed { i, p ->
                val n = Regex("LANPort\\.(\\d+)").find(p["domain"])?.groupValues?.get(1) ?: "${i + 1}"
                Row("LAN $n", if (p["Status"] == "1") "Connected" else "Not connected")
            })
        }
        return cards
    }

    fun deviceInfo(d: PageData): List<Card> {
        val info = d.first("stDeviceInfo")
        val ont = d.first("ONTInfo")
        val rows = ArrayList<Row>()
        if (info != null) rows += Fmt.rows(info, listOf("ModelName", "Description", "SerialNumber", "HardwareVersion", "SoftwareVersion", "VendorID", "ReleaseTime", "Mac", "ManufactureInfo"))
        rows += Fmt.r("ONT ID", ont?.get("ONTID"))
        rows += Fmt.r("ONT status", ont?.get("Status"))
        rows += Fmt.r("PON mode", d.v("ontPonMode").uppercase())
        rows += Fmt.r("CPU usage", d.v("cpuUsed"))
        rows += Fmt.r("Memory usage", d.v("memUsed"))
        rows += Fmt.r("System time", d.v("systemdsttime"))
        rows += Fmt.r("Default admin password still set", if (d.v("IsDefaultPwd") == "1") "Yes" else "No")
        return listOf(Card("Device information", rows))
    }

    fun optical(d: PageData): List<Card> {
        val cards = ArrayList<Card>()
        val o = d.first("stOpticInfo")
        if (o != null) {
            val rx = o["revOpticPower"].trim()
            val good = opticRxGood(rx)
            cards += Card(
                "Optical module",
                Fmt.rows(o, listOf("transOpticPower", "revOpticPower", "voltage", "temperature", "bias", "rfRxPower", "rfOutputPower", "LosStatus", "TxWaveLength", "RxWaveLength", "MaxTxDistance", "VendorName", "VendorSN", "DateCode")),
                big = Fmt.value("revOpticPower", rx),
                good = good,
                note = when (good) {
                    true -> "Rx power is in the normal range (-8 to -27 dBm)."
                    false -> "Rx power is outside the normal range (-8 to -27 dBm). Check the fibre cable."
                    null -> null
                }
            )
        }
        val linkTime = d.v("LinkTime").toLongOrNull()
        cards += Card(
            "PON link",
            listOf(
                Fmt.r("PON mode", d.v("ontPonMode").uppercase()),
                Fmt.r("Link up for", linkTime?.let { Fmt.duration(it) }),
                Fmt.r("PON packets sent", d.v("PONTxPackets", 0)),
                Fmt.r("PON packets received", d.v("PONTxPackets", 1)),
                Fmt.r("Optical power", d.v("opticPower"))
            )
        )
        d.first("stOLTOpticInfo")?.let { cards += Card("OLT side", Fmt.rows(it)) }
        return cards
    }

    /** GetDeviceState.asp returns "HW_WEB_GetDeviceStatus,dev,olt,ems,acs" - same logic as the router page. */
    fun provisioning(d: PageData): List<Card> {
        val text = d.texts.values.firstOrNull { it.contains("GetDeviceStatus") }
            ?.let { HwParser.unescape(it).trim() } ?: return listOf(Card("Provisioning", note = "No status returned by the router."))
        val p = text.split(",").map { it.trim() }
        val dev = p.getOrElse(1) { "" }
        val olt = p.getOrElse(2) { "" }
        val ems = p.getOrElse(3) { "" }
        val acs = p.getOrElse(4) { "" }.toIntOrNull()

        var ontReg = "--"; var oltCfg = "--"; var emsCfg = "--"; var acsReg = "--"
        if (dev != "0") {
            ontReg = "Still registering / not registered with the OLT"
        } else {
            ontReg = "Registered with the OLT"
            oltCfg = when (olt) {
                "0x0" -> "OLT service configured"
                "0xffffffff" -> if (ems == "2" && acs != 0) "Status not available (OLT version does not match)" else "OLT service configured"
                else -> "OLT service configuration failed"
            }
            emsCfg = when (ems) {
                "0" -> "EMS service configured"
                "1" -> "EMS is applying configuration"
                "2" -> "No XML configuration applied"
                else -> "EMS configuration failed"
            }
            acsReg = when (acs) {
                0 -> "Registered with the ACS server"
                1 -> "--"
                2 -> "Registering with the ACS server"
                else -> "ACS registration failed"
            }
        }
        return listOf(
            Card(
                "Service provisioning",
                listOf(
                    Row("ONT registration", ontReg),
                    Row("OLT service configuration", oltCfg),
                    Row("EMS configuration", emsCfg),
                    Row("ACS (TR-069) registration", acsReg),
                    Row("Raw codes", p.drop(1).joinToString(", "))
                ),
                good = dev == "0"
            )
        )
    }

    fun ethPorts(d: PageData): List<Card> {
        val ports = d.all("GEInfo")
        if (ports.isEmpty()) return emptyList()
        return listOf(Card("Port link status", ports.mapIndexed { i, p ->
            val n = Regex("LANPort\\.(\\d+)").find(p["domain"])?.groupValues?.get(1) ?: "${i + 1}"
            Row("LAN $n", if (p["Status"] == "1") "Up (cable connected)" else "Down")
        }))
    }

    fun wlanInfo(d: PageData): List<Card> {
        val cards = ArrayList<Card>()
        val stats = d.all("stPacketInfo")
        val errs = d.all("stStats")
        d.all("stWlan").forEach { w ->
            val rows = ArrayList<Row>()
            rows += Fmt.rows(w, listOf("ssid", "enable", "X_HW_ServiceEnable", "wlHide", "BeaconType", "X_HW_Standard", "Channel", "TransmitPower", "X_HW_HT20", "RegulatoryDomain", "wmmEnable", "DeviceNum", "name"))
            // the encryption field name differs per page version
            listOf("WPAand11iEncrypt", "IEEE11iEncrypt", "WPAEncrypt").firstOrNull { w[it].isNotBlank() }?.let {
                if (rows.none { r -> r.label == "Encryption" }) rows += Row("Encryption", Fmt.encryptionName(w[it]))
            }
            stats.firstOrNull { it["domain"] == w["domain"] }?.let { rows += Fmt.rows(it) }
            errs.firstOrNull { it["domain"].startsWith(w["domain"]) }?.let { rows += Fmt.rows(it) }
            cards += Card("SSID: ${w["ssid"]}", rows)
        }
        d.first("stDeviceMac")?.let { cards += Card("MAC addresses", Fmt.rows(it)) }

        val names = d.all("USERDevice").associate { it["MacAddr"].lowercase() to it["HostName"] }
        val clients = d.all("stAssociatedDevice").filterNot { Fmt.isPlaceholder(it) }
        if (clients.isEmpty()) {
            cards += Card("WiFi clients", note = "No WiFi client details returned.")
        } else clients.forEach { c ->
            val mac = c["AssociatedDeviceMACAddress"]
            val name = names[mac.lowercase()].orEmpty().ifBlank { mac }
            cards += Card("WiFi client: $name", Fmt.rows(c))
        }
        val aps = d.all("stNeighbourAP").filterNot { Fmt.isPlaceholder(it) }
        if (aps.isNotEmpty()) aps.forEach { cards += Card("Nearby: ${it["SSID"]}", Fmt.rows(it)) }
        return cards
    }

    fun layer3(d: PageData): List<Card> =
        d.all("stLayer3Enable").mapIndexed { i, rec ->
            Card("LAN ${i + 1}", listOf(Row("Mode", if (rec["lay3enable"] == "1") "Layer 3 (routed)" else "Layer 2 (bridged)")))
        }

    fun firewall(d: PageData): List<Card> =
        listOf(Card("Firewall level", listOf(Fmt.r("Current level", d.v("FltsecLevelx")))))

    fun upnp(d: PageData): List<Card> =
        listOf(Card("UPnP", listOf(Fmt.r("UPnP", if (d.v("enblMainUpnp") == "1") "On" else "Off"))))

    fun firmware(d: PageData): List<Card> {
        val info = d.first("stDeviceInfo")
        return listOf(Card(
            "Current firmware",
            listOf(Fmt.r("Software version", info?.get("SoftwareVersion")), Fmt.r("Release date", info?.get("ReleaseTime")), Fmt.r("Hardware", info?.get("HardwareVersion"))),
            note = "Uploading new firmware is not done from the app (too risky on a phone - use a PC)."
        ))
    }

    fun ontAuth(d: PageData): List<Card> {
        val dev = d.first("stDevInfo")
        val rows = arrayListOf(Fmt.r("ONT serial number (SN)", dev?.get("serialnumber")))
        listOf("LOID", "loid", "Loid", "PwdMode", "ontPonMode").forEach { k -> d.v(k).takeIf { it.isNotBlank() }?.let { rows += Row(Fmt.pretty(k), it) } }
        return listOf(Card("ONT authentication", rows))
    }
}

/* ------------------------------------------------------------------ */
/*  The menu - same groups as the router's own admin panel             */
/* ------------------------------------------------------------------ */

object Sections {

    private const val WAN_INFO = "html/bbsp/common/wan_list_info.asp"
    private const val WAN_LIST = "html/bbsp/common/wan_list.asp"
    private const val USER_DEVS = "html/bbsp/common/GetLanUserDevInfo.asp"

    private val WAN_STATUS_FIELDS = listOf(
        "Name", "ConnectionStatus", "LastConnErr", "Mode", "IPAddress", "Gateway", "dnsstr", "Uptime",
        "Username", "MACAddress", "VlanId", "Pri8021", "ServiceList", "NATEnable", "IPv4Enable", "IPv6Enable",
        "PPPoESessionID", "PPPoEACName", "MaxMRUSize"
    )
    private val WAN_CONFIG_FIELDS = listOf(
        "Name", "Enable", "Mode", "ServiceList", "Username", "DialMode", "ConnectionTrigger", "VlanId",
        "MultiVlanID", "PriPolicy", "Pri8021", "DefaultPri", "NATEnable", "X_HW_NatType", "IPv4Enable",
        "IPv6Enable", "MaxMRUSize", "LcpEchoReqCheck", "IdleDisconnectTime", "X_HW_IdleDetectMode",
        "X_HW_IGMPEnable", "DNSOverrideAllowed", "Tr069Flag", "MACAddress", "MacId"
    )

    val home = Section(
        "home", "Home Page", "Status",
        listOf(Src(WAN_INFO), Src(WAN_LIST), Src(USER_DEVS, post = true), Src("html/ssmp/deviceinfo/deviceinfo.asp"),
            Src("html/amp/opticinfo/opticinfo.asp"), Src("html/amp/common/wlan_list.asp")),
        classes = emptyList(), custom = Custom::home
    )

    val all: List<Section> = listOf(
        home,
        Section("devices_info", "Connected Devices", "Status",
            listOf(Src(USER_DEVS, post = true), Src("html/bbsp/common/GetLanUserDhcpInfo.asp", post = true)),
            classes = emptyList(), custom = Custom::allDevices, emptyText = "No devices found."),
        Section("diagnose", "One-Click Diagnosis", "Status",
            listOf(Src("html/amp/common/getSmartDiagnoseResult.asp", post = true, token = true),
                Src("html/ssmp/maintain/smartdiagnose.asp"), Src(WAN_INFO), Src(WAN_LIST)),
            classes = emptyList(), custom = Custom::diagnosis,
            note = "Shows the router's last health results. Running a new full self-test is not included."),

        // ---- System Info
        Section("deviceinfo", "Device Information", "System Info",
            listOf(Src("html/ssmp/deviceinfo/deviceinfo.asp")), classes = emptyList(), custom = Custom::deviceInfo),
        Section("waninfo", "WAN Information", "System Info",
            listOf(Src(WAN_INFO), Src(WAN_LIST), Src("html/bbsp/common/wanipv6state.asp"), Src("html/bbsp/waninfo/waninfo.asp")),
            classes = listOf("WanPPP", "WanIP", "IPv6WanInfo", "PONPackageInfo"),
            fields = mapOf(
                "WanPPP" to WAN_STATUS_FIELDS, "WanIP" to WAN_STATUS_FIELDS,
                "IPv6WanInfo" to listOf("ConnectionStatus", "Type", "L2EncapType", "DNSServers", "AFTRName", "AFTRPeerAddr", "DefaultRouterAddress", "V6UpTime")
            )),
        Section("opticinfo", "Optical Information", "System Info",
            listOf(Src("html/amp/opticinfo/opticinfo.asp")), classes = emptyList(), custom = Custom::optical),
        Section("bssinfo", "Service Provisioning Status", "System Info",
            listOf(Src("html/ssmp/common/GetDeviceState.asp", post = true)), classes = emptyList(), custom = Custom::provisioning),
        Section("ethinfo", "Eth Port Information", "System Info",
            listOf(Src("html/amp/ethinfo/ethinfo.asp"), Src("html/ssmp/maintain/smartdiagnose.asp")),
            custom = Custom::ethPorts),
        Section("wlaninfo", "WLAN Information", "System Info",
            listOf(Src("html/amp/wlaninfo/wlaninfo.asp"), Src("html/amp/wlaninfo/getassociateddeviceinfo.asp", post = true),
                Src("html/amp/wlaninfo/getneighbourAPinfo.asp", post = true), Src(USER_DEVS, post = true)),
            classes = emptyList(), custom = Custom::wlanInfo),
        Section("wlancoverinfo", "Smart WiFi Coverage", "System Info",
            listOf(Src("html/amp/wificoverinfo/wlancoverinfo.asp")),
            emptyText = "No WiFi extenders / APs connected to this router."),

        // ---- WAN
        Section("wanconfig", "WAN Configuration", "Advanced: WAN",
            listOf(Src(WAN_INFO), Src(WAN_LIST), Src("html/bbsp/wan/wan.asp")),
            classes = listOf("WanPPP", "WanIP", "TDEPPPWanIPv6AddressClass", "TDE_PPP_DelegationEnabledClass", "GetPppWan6RDTunnelInfo", "DsLiteInfo", "PolicyRouteItem"),
            fields = mapOf("WanPPP" to WAN_CONFIG_FIELDS, "WanIP" to WAN_CONFIG_FIELDS)),

        // ---- LAN
        Section("layer3", "Layer 2/3 Port Configuration", "Advanced: LAN",
            listOf(Src("html/bbsp/layer3/layer3.asp")), classes = emptyList(), custom = Custom::layer3),
        Section("lanhost", "LAN Host Configuration", "Advanced: LAN",
            listOf(Src("html/bbsp/dhcp/dhcp.asp")), classes = listOf("stLanHostInfo", "madhcpst", "SlaveDhcpInfo")),
        Section("dhcp", "DHCP Server Configuration", "Advanced: LAN",
            listOf(Src("html/bbsp/dhcpservercfg/dhcp2.asp")), classes = listOf("dhcpmainst", "dhcpcnst")),
        Section("dhcpstatic", "DHCP Static IP Configuration", "Advanced: LAN",
            listOf(Src("html/bbsp/dhcpstatic/dhcpstatic.asp")), emptyText = "No static IP (DHCP reservation) entries."),
        Section("dhcpv6", "DHCPv6 Server Configuration", "Advanced: LAN",
            listOf(Src("html/bbsp/lanaddress/lanaddress.asp")),
            classes = listOf("DhcpV6Server", "stLanDhcp6Info", "Br0IPv6AddressClass", "Br0IPv6PrefixClass", "RaConfigInfoClass", "UlaModeInfoClass", "UlaConfigInfoClass", "IPv6DNSConfigClass")),
        Section("dhcpv6static", "DHCPv6 Static IP Configuration", "Advanced: LAN",
            listOf(Src("html/bbsp/dhcpstaticaddr/dhcpstaticaddress.asp")), emptyText = "No DHCPv6 static entries.",
            classes = null),
        Section("dhcpv6info", "DHCPv6 Information", "Advanced: LAN",
            listOf(Src("html/bbsp/dhcpv6info/dhcpv6info.asp"))),

        // ---- Security
        Section("firewall", "IPv4 Firewall Level", "Advanced: Security",
            listOf(Src("html/bbsp/firewalllevel/firewalllevel.asp")), classes = emptyList(), custom = Custom::firewall),
        Section("dos", "DoS Configuration", "Advanced: Security", listOf(Src("html/bbsp/Dos/Dos.asp"))),
        Section("ipfilter", "IPv4 Address Filtering", "Advanced: Security",
            listOf(Src("html/bbsp/ipincoming/ipincoming.asp")), emptyText = "No IP filter rules."),
        Section("macfilter", "MAC Address Filtering", "Advanced: Security",
            listOf(Src("html/bbsp/macfilter/macfilter.asp")), emptyText = "No MAC filter rules."),
        Section("wlanmacfilter", "Wi-Fi MAC Address Filtering", "Advanced: Security",
            listOf(Src("html/bbsp/wlanmacfilter/wlanmacfilter.asp")), emptyText = "No WiFi MAC filter rules."),
        Section("parental", "Parental Control", "Advanced: Security",
            listOf(Src("html/bbsp/parentalctrl/parentalctrlstatus.asp"), Src("html/bbsp/common/parentalctrlinfo.asp")),
            classes = listOf("TemplatesListClass", "StatsListClass"), emptyText = "No parental control set up."),
        Section("acl", "Device Access Control", "Advanced: Security", listOf(Src("html/bbsp/acl/aclsmart.asp"))),
        Section("wanacl", "WAN Access Control", "Advanced: Security",
            listOf(Src("html/bbsp/wanacl/wanacl.asp"), Src("html/bbsp/common/wanaccesslist.asp")),
            emptyText = "No WAN access rules."),

        // ---- Route
        Section("route", "Default IPv4 Route", "Advanced: Route", listOf(Src("html/bbsp/route/route.asp"))),
        Section("staticroute", "IPv4 Static Route", "Advanced: Route",
            listOf(Src("html/bbsp/staticroute/staticroute.asp")), emptyText = "No static routes."),
        Section("dynamicroute", "IPv4 Dynamic Route", "Advanced: Route", listOf(Src("html/bbsp/dynamicroute/dynamicroute.asp"))),
        Section("vlanbind", "IPv4 VLAN Binding", "Advanced: Route", listOf(Src("html/bbsp/vlanctc/vlanctc.asp"))),
        Section("serviceroute", "IPv4 Service Route", "Advanced: Route",
            listOf(Src("html/bbsp/serviceroute/serviceroute.asp")), emptyText = "No service routes."),
        Section("routeinfo", "IPv4 Routing Table", "Advanced: Route", listOf(Src("html/bbsp/routeinfo/routeinfo.asp")), classes = listOf("RouteInfo")),
        Section("ipv6route", "Default IPv6 Route", "Advanced: Route",
            listOf(Src("html/bbsp/ipv6defaultroute/defaultroute.asp")), emptyText = "No IPv6 default route set."),
        Section("ipv6static", "IPv6 Static Route", "Advanced: Route",
            listOf(Src("html/bbsp/ipv6staticroute/ipv6staticroute.asp"), Src("html/bbsp/common/ipv6staticroute.asp")),
            emptyText = "No IPv6 static routes."),

        // ---- Forward rules
        Section("dmz", "DMZ Function", "Advanced: Forward Rules",
            listOf(Src("html/bbsp/dmz/dmz.asp")), emptyText = "DMZ is not set up."),
        Section("portmapping", "IPv4 Port Mapping", "Advanced: Forward Rules",
            listOf(Src("html/bbsp/portmapping/portmappingnew.asp")), classes = null, emptyText = "No port mappings set up."),
        Section("porttrigger", "Port Trigger", "Advanced: Forward Rules",
            listOf(Src("html/bbsp/porttrigger/porttrigger.asp")), classes = listOf("stPortTrigger"), emptyText = "No port triggers."),

        // ---- Application
        Section("sntp", "Time Setting", "Advanced: Application", listOf(Src("html/ssmp/sntp/sntp.asp")), classes = listOf("stTimeInfo")),
        Section("alg", "ALG Configuration", "Advanced: Application", listOf(Src("html/bbsp/alg/alg.asp"))),
        Section("ddns", "DDNS Function", "Advanced: Application", listOf(Src("html/bbsp/ddns/ddns.asp"))),
        Section("upnp", "UPnP Function", "Advanced: Application", listOf(Src("html/bbsp/upnp/upnp.asp")), custom = Custom::upnp),
        Section("igmp", "IGMP Configuration", "Advanced: Application", listOf(Src("html/bbsp/igmp/igmp.asp"))),
        Section("qossmart", "Intelligent Channel", "Advanced: Application", listOf(Src("html/bbsp/qossmart/qossmart.asp"))),
        Section("dns", "Static DNS", "Advanced: Application",
            listOf(Src("html/bbsp/dnsconfiguration/dnsconfigcommon.asp"), Src("html/bbsp/common/dnshostslist.asp")),
            emptyText = "No static DNS entries."),
        Section("dscp", "DSCP-to-Pbit Mapping", "Advanced: Application",
            listOf(Src("html/bbsp/dscptopbit/dscptopbit.asp")), emptyText = "No DSCP mappings."),
        Section("qosbasic", "QoS Basic", "Advanced: Application",
            listOf(Src("html/bbsp/qos/qosbasic.asp"), Src("html/bbsp/common/qosinfoe8c.asp")), emptyText = "QoS is not set up."),
        Section("qosadv", "QoS Advance", "Advanced: Application",
            listOf(Src("html/bbsp/qos/qosadvanced.asp")), emptyText = "No advanced QoS rules."),

        // ---- WLAN
        Section("wlanbasic", "WLAN Basic Configuration", "Advanced: WLAN",
            listOf(Src("html/amp/wlanbasic/WlanBasic.asp")),
            classes = listOf("stWlanWifi", "stWlan", "stWpsPin", "stLanDevice"), editWifi = true,
            fields = mapOf("stWlan" to listOf("ssid", "enable", "X_HW_ServiceEnable", "wlHide", "BeaconType", "X_HW_WPAand11iEncryptionModes", "DeviceNum", "wmmEnable", "IsolationEnable", "WPARekey", "name"))),
        Section("wlanadv", "WLAN Advanced Configuration", "Advanced: WLAN",
            listOf(Src("html/amp/wlanadv/WlanAdvance.asp")), classes = listOf("stWlanWifi", "stWlanAdv", "stWiFiRadio", "stXHWGlobalConfig")),
        Section("wlanschedule", "Automatic WiFi Shutdown", "Advanced: WLAN",
            listOf(Src("html/amp/wifische/WlanSchedule.asp")), emptyText = "No automatic WiFi off times set."),
        Section("wificover", "WiFi Coverage Management", "Advanced: WLAN",
            listOf(Src("html/amp/wificovercfg/wifiCover.asp")), classes = listOf("stWifiCoverService", "stConfigurationByRadio")),

        // ---- System management
        Section("tr069", "TR-069", "System Management", listOf(Src("html/ssmp/tr069/tr069.asp"))),
        Section("accounts", "Account Management", "System Management",
            listOf(Src("html/ssmp/accoutcfg/accountadmin.asp")), classes = listOf("stModifyUserInfo", "stSSLWeb")),
        Section("ontauth", "ONT Authentication", "System Management",
            listOf(Src("html/amp/ontauth/passwordcommon.asp")), classes = emptyList(), custom = Custom::ontAuth),
        Section("firmware", "Software Upgrade", "System Management",
            listOf(Src("html/ssmp/deviceinfo/deviceinfo.asp")), classes = emptyList(), custom = Custom::firmware),

        // ---- Maintenance
        Section("maintain", "Maintenance (Ping / Traceroute)", "Maintenance Diagnosis",
            listOf(Src("html/bbsp/maintenance/diagnosecommon.asp")), classes = listOf("PingResultClass", "TracertResultClass"),
            note = "Shows the last ping / traceroute result saved on the router."),
        Section("qosstats", "Intelligent Channel Statistics", "Maintenance Diagnosis",
            listOf(Src("html/bbsp/qossmartstatistics/qossmartstatistics.asp"), Src("html/bbsp/qossmartstatistics/GetQosStatisticsResult.asp", post = true)),
            emptyText = "No traffic statistics (intelligent channel is off).")
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
