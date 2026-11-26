package io.github.ilevs.p2pmusic.search

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Minimal SOCKS5 server (RFC 1928 + 1929, CONNECT only) for tests. */
class Socks5TestServer(
    private val username: String,
    private val password: String,
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    @Volatile
    var lastHost: String? = null
        private set

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    override fun close() = server.close()

    private fun serve(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        input.readByte() // version
        input.skipBytes(input.readUnsignedByte()) // offered methods
        output.write(byteArrayOf(VERSION, USER_PASSWORD_METHOD))
        input.readByte() // auth version
        val user = String(input.bytes(input.readUnsignedByte()))
        val pass = String(input.bytes(input.readUnsignedByte()))
        val isAllowed = user == username && pass == password
        output.write(byteArrayOf(1, if (isAllowed) 0 else 1))
        if (!isAllowed) return
        input.skipBytes(REQUEST_HEADER) // version, command, reserved
        val host =
            when (input.readUnsignedByte()) {
                DOMAIN -> String(input.bytes(input.readUnsignedByte()))
                IPV4 -> InetAddress.getByAddress(input.bytes(IPV4_SIZE)).hostAddress
                else -> InetAddress.getByAddress(input.bytes(IPV6_SIZE)).hostAddress
            }
        val targetPort = input.readUnsignedShort()
        lastHost = host
        Socket(host, targetPort).use { target ->
            output.write(byteArrayOf(VERSION, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            val upstream = thread(isDaemon = true) { pipe(input, target.getOutputStream()) }
            pipe(target.getInputStream(), output)
            upstream.join()
        }
    }

    private fun DataInputStream.bytes(count: Int): ByteArray = ByteArray(count).also(::readFully)

    private fun pipe(
        from: InputStream,
        to: OutputStream,
    ) {
        try {
            from.copyTo(to)
            to.flush()
        } catch (_: IOException) {
            // One side closed the connection: the relay is over.
        }
    }

    private companion object {
        const val BACKLOG = 16
        const val VERSION: Byte = 5
        const val USER_PASSWORD_METHOD: Byte = 2
        const val REQUEST_HEADER = 3
        const val IPV4 = 1
        const val DOMAIN = 3
        const val IPV4_SIZE = 4
        const val IPV6_SIZE = 16
    }
}
