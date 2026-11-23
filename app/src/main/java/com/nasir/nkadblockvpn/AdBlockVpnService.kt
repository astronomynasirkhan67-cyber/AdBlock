package com.nasir.nkadblockvpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AdBlockVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val alive = AtomicBoolean(false)
    companion object {
        const val ACTION_STOP = "com.nasir.nkadblockvpn.action.STOP"
        @Volatile var running = false
        val blockedCount = AtomicInteger(0)
        private const val DNS_IP = "10.10.10.2"
        private val blockedDomains = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adform.net", "adsrvr.org",
            "app-measurement.com", "unityads.unity3d.com", "applovin.com",
            "ironsrc.com", "adjust.com", "scorecardresearch.com"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownVpn()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            createNotification()
            if (running) return START_STICKY
            tun = Builder().setSession("NK Ad Block VPN")
                .addAddress("10.10.10.1", 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)
                .setMtu(1500)
                .establish()
            if (tun == null) { stopSelf(); return START_NOT_STICKY }
            alive.set(true); running = true
            thread(name = "NK-DNS-filter") { packetLoop() }
        } catch (e: Exception) {
            Log.e("NKAdBlockVPN", "Unable to start local VPN", e)
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotification() {
        val channel = "nk_adblock"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "NK Ad Block VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, channel).setContentTitle("NK Ad Block VPN")
                .setContentText("DNS protection is active").setSmallIcon(android.R.drawable.ic_secure).build()
        else Notification.Builder(this).setContentTitle("NK Ad Block VPN")
            .setContentText("DNS protection is active").setSmallIcon(android.R.drawable.ic_secure).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(42, n)
        }
    }

    private fun packetLoop() {
        val input = FileInputStream(tun!!.fileDescriptor)
        val output = FileOutputStream(tun!!.fileDescriptor)
        val buf = ByteArray(32767)
        while (alive.get()) {
            try {
                val n = input.read(buf)
                if (n < 28) continue
                val packet = buf.copyOf(n)
                if ((packet[0].toInt() shr 4 and 0xF) != 4) continue
                val ihl = (packet[0].toInt() and 0xF) * 4
                if (packet[9].toInt() != 17 || n < ihl + 8) continue
                val destPort = u16(packet, ihl + 2)
                if (destPort != 53) continue
                val dnsOffset = ihl + 8
                val dnsLen = n - dnsOffset
                if (dnsLen < 17) continue
                val domain = readDomain(packet, dnsOffset, dnsLen) ?: continue
                val response = if (blockedDomains.any { domain == it || domain.endsWith(".$it") }) {
                    blockedCount.incrementAndGet()
                    makeDnsResponse(packet.copyOfRange(dnsOffset, n), true)
                } else {
                    forwardDns(packet.copyOfRange(dnsOffset, n))
                } ?: continue
                val reply = makeIpReply(packet, ihl, response)
                output.write(reply)
            } catch (_: Exception) {
                if (!alive.get()) break
            }
        }
    }

    private fun forwardDns(query: ByteArray): ByteArray? {
        return try {
            val socket = DatagramSocket()
            try {
                protect(socket)
                socket.soTimeout = 2500
                val addr = InetAddress.getByName("94.140.14.14")
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val data = ByteArray(4096)
                val p = DatagramPacket(data, data.size)
                socket.receive(p)
                data.copyOf(p.length)
            } finally { socket.close() }
        } catch (_: Exception) { null }
    }

    private fun readDomain(p: ByteArray, off: Int, len: Int): String? {
        try {
            var i = off + 12
            val end = off + len
            val labels = mutableListOf<String>()
            while (i < end) {
                val size = p[i++].toInt() and 255
                if (size == 0) break
                if (size > 63 || i + size > end) return null
                labels.add(String(p, i, size, Charsets.US_ASCII)); i += size
            }
            return labels.joinToString(".").lowercase()
        } catch (_: Exception) { return null }
    }

    private fun makeDnsResponse(q: ByteArray, blocked: Boolean): ByteArray {
        val r = q.copyOf()
        r[2] = 0x81.toByte()
        r[3] = if (blocked) 0x83.toByte() else 0x80.toByte()
        r[6] = 0; r[7] = 0
        r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        return r
    }

    private fun makeIpReply(original: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val out = ByteArray(ihl + 8 + dns.size)
        System.arraycopy(original, 0, out, 0, ihl)
        // Swap IPv4 source and destination
        for (i in 0..3) {
            out[12+i] = original[16+i]; out[16+i] = original[12+i]
        }
        out[2] = (out.size shr 8).toByte(); out[3] = out.size.toByte()
        out[10] = 0; out[11] = 0
        val ipSum = checksum(out, 0, ihl)
        out[10] = (ipSum shr 8).toByte(); out[11] = ipSum.toByte()
        out[ihl] = original[ihl+2]; out[ihl+1] = original[ihl+3]
        out[ihl+2] = original[ihl]; out[ihl+3] = original[ihl+1]
        val udpLen = 8 + dns.size
        out[ihl+4] = (udpLen shr 8).toByte(); out[ihl+5] = udpLen.toByte()
        out[ihl+6] = 0; out[ihl+7] = 0
        System.arraycopy(dns, 0, out, ihl+8, dns.size)
        return out
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off
        while (i + 1 < off + len) {
            sum += (((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)).toLong(); i += 2
        }
        if (i < off + len) sum += ((b[i].toInt() and 255) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)

    private fun shutdownVpn() {
        alive.set(false)
        running = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    override fun onRevoke() {
        shutdownVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdownVpn()
        super.onDestroy()
    }
}
