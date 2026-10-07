package com.cpagency.wifimanager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * Network helpers.
 *
 * Important: when the home WiFi has no internet, Android quietly moves the
 * phone's traffic to mobile data. Then "is Google reachable?" would say YES
 * (over 4G) and the router at 192.168.100.1 would look DOWN. So every check
 * here is pinned to the WiFi network.
 */
object NetUtil {
    @Volatile var appContext: Context? = null

    fun wifiNetwork(context: Context? = appContext): Network? {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) { null }
    }

    /** Socket factory that always opens sockets on the WiFi network (falls back to default). */
    object WifiSocketFactory : SocketFactory() {
        private fun base(): SocketFactory = wifiNetwork()?.socketFactory ?: getDefault()
        override fun createSocket(): Socket = base().createSocket()
        override fun createSocket(host: String?, port: Int): Socket = base().createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            base().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket = base().createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            base().createSocket(address, port, localAddress, localPort)
    }

    /**
     * Time (ms) to reach [host]:[port] over WiFi, or null if unreachable.
     * A "connection refused" answer also proves the host is reachable
     * (it replied), so it counts as success - handy for ISP gateways
     * that don't run any service.
     */
    fun probe(host: String, port: Int, timeoutMs: Int = 2500, network: Network? = wifiNetwork()): Long? {
        if (network == null) return null
        val start = System.nanoTime()
        val s = try { network.socketFactory.createSocket() } catch (_: Exception) { return null }
        return try {
            s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000
        } catch (e: ConnectException) {
            val ms = (System.nanoTime() - start) / 1_000_000
            // refused quickly = host answered; a slow "failure" is really a timeout/unreachable
            if (e.message?.contains("refused", true) == true && ms < timeoutMs) ms else null
        } catch (_: SocketTimeoutException) { null
        } catch (_: Exception) { null
        } finally {
            try { s.close() } catch (_: Exception) { }
        }
    }
}
