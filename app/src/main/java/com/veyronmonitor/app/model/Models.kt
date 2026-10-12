package com.veyronmonitor.app.model

data class AuthState(
    val token: String,
    val vrtKey: String
)

data class Device(
    val serialNumber: String,
    val displayName: String,
    val isOnline: Boolean
)

/**
 * Our own reliability signal, based on how recently a genuinely NEW
 * reading arrived -- not the cloud's own onlineStatus flag, which can
 * flap on slow/congested WiFi even while the dongle is still reporting.
 */
enum class ConnectionStatus { LIVE, RECENT, OFFLINE }
