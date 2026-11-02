package com.deepseekharness.app.phone

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The phone bridge: a unix-domain socket in the abstract namespace that the guest relay
 * connects to. proot passes abstract addresses through untranslated (filesystem socket paths are
 * rewritten into the rootfs and may exceed sun_path), so a Node process inside proot reaches it.
 *
 * Every connection must come from this app's uid (SO_PEERCRED) and start with a hello line
 * carrying this launch's token; then it is MCP over newline-delimited JSON-RPC, served by
 * [McpServer]. A per-launch random socket name keeps other apps from squatting it.
 *
 * Fallback: loopback TCP + token, opened only when asked (see [PhoneControl]); the
 * peer uid is checked through /proc/net/tcp when readable. Any app can reach loopback, so this
 * is weaker and used only on devices where the abstract socket turns out unreachable.
 *
 * Same-uid code (anything in the rootfs) gets past both checks by design: the real gate is the
 * person's tap on the confirmation.
 */
class PhoneBridge(
    private val server: McpServer,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    private var local: LocalServerSocket? = null
    private var tcp: ServerSocket? = null
    private val open = Collections.synchronizedSet(HashSet<Closeable>())
    private val calls = Executors.newCachedThreadPool { r -> Thread(r, "dsh-phone-call").apply { isDaemon = true } }
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val authed = AtomicInteger()

    @Volatile var endpoint: PhoneGuest.Endpoint? = null
        private set

    val listening: Boolean get() = running
    /** Connections that passed uid + token since [start] (DSH's probe plus the serving relay). */
    val authenticatedConnections: Int get() = authed.get()

    /** Binds a fresh abstract socket (and TCP when [withTcp]); returns what the relay needs. */
    @Synchronized
    fun start(withTcp: Boolean): PhoneGuest.Endpoint {
        stop()
        val token = hex(32)
        var name: String? = null
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            val candidate = if (attempt == 0) "dsha.phone.bridge" else "dsha.phone." + hex(8)
            try {
                local = LocalServerSocket(candidate)
                name = candidate
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (name == null) log("[dsh-app] phone bridge: abstract socket failed (${lastError?.message}); TCP only")
        var port: Int? = null
        if (withTcp || name == null) {
            tcp = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { port = it.localPort }
        }
        running = true
        authed.set(0)
        val ep = PhoneGuest.Endpoint(name, port, token)
        endpoint = ep
        local?.let { s -> thread(name = "dsh-phone-accept", isDaemon = true) { acceptLocal(s, token) } }
        tcp?.let { s -> thread(name = "dsh-phone-accept-tcp", isDaemon = true) { acceptTcp(s, token) } }
        log("[dsh-app] phone bridge listening" + (name?.let { " on @$it" } ?: "") + (port?.let { " and 127.0.0.1:$it" } ?: ""))
        return ep
    }

    /** Adds the loopback TCP listener to a running bridge (abstract socket unreachable from the guest). */
    @Synchronized
    fun addTcp(): PhoneGuest.Endpoint? {
        val ep = endpoint ?: return null
        if (!running) return null
        if (ep.tcpPort != null) return ep
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        tcp = s
        val next = ep.copy(tcpPort = s.localPort)
        endpoint = next
        thread(name = "dsh-phone-accept-tcp", isDaemon = true) { acceptTcp(s, ep.token) }
        log("[dsh-app] phone bridge: added loopback fallback on 127.0.0.1:${s.localPort}")
        return next
    }

    @Synchronized
    fun stop() {
        if (!running && local == null && tcp == null) return
        running = false
        endpoint = null
        local?.let { s ->
            // close() alone doesn't wake accept() on every Android version: shut the fd down first.
            runCatching { Os.shutdown(s.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { s.close() }
        }
        local = null
        tcp?.let { runCatching { it.close() } }
        tcp = null
        synchronized(open) {
            open.forEach { runCatching { it.close() } }
            open.clear()
        }
    }

    private fun acceptLocal(s: LocalServerSocket, token: String) {
        while (running) {
            val sock: LocalSocket = try {
                s.accept()
            } catch (e: Exception) {
                if (running) log("[dsh-app] phone bridge: accept failed: ${e.message}")
                return
            }
            if (!running) {
                runCatching { sock.close() }
                return
            }
            val uid = try {
                sock.peerCredentials.uid
            } catch (_: Exception) {
                -1
            }
            if (uid != Process.myUid() && uid != 0) {
                log("[dsh-app] phone bridge: refused a connection from uid $uid")
                runCatching { sock.close() }
                continue
            }
            serve(sock, sock.inputStream, sock.outputStream, token) { ms -> runCatching { sock.soTimeout = ms } }
        }
    }

    private fun acceptTcp(s: ServerSocket, token: String) {
        while (running) {
            val sock: Socket = try {
                s.accept()
            } catch (e: Exception) {
                if (running) log("[dsh-app] phone bridge: tcp accept failed: ${e.message}")
                return
            }
            if (!running) {
                runCatching { sock.close() }
                return
            }
            val uid = loopbackPeerUid(sock.port, s.localPort)
            if (uid != null && uid != Process.myUid() && uid != 0) {
                log("[dsh-app] phone bridge: refused a tcp connection from uid $uid")
                runCatching { sock.close() }
                continue
            }
            serve(sock, sock.getInputStream(), sock.getOutputStream(), token) { ms -> runCatching { sock.soTimeout = ms } }
        }
    }

    private fun serve(sock: Closeable, raw: InputStream, output: OutputStream, token: String, timeout: (Int) -> Unit) {
        val input = java.io.BufferedInputStream(raw)
        if (!slots.tryAcquire()) {
            runCatching { sock.close() }
            return
        }
        open += sock
        thread(name = "dsh-phone-conn", isDaemon = true) {
            try {
                timeout(HELLO_TIMEOUT_MS)
                val hello = readLine(input, MAX_HELLO) ?: return@thread
                if (!helloOk(hello, token)) {
                    log("[dsh-app] phone bridge: bad token, connection closed")
                    return@thread
                }
                authed.incrementAndGet()
                timeout(0)
                val lock = Object()
                fun send(line: String) {
                    synchronized(lock) {
                        output.write((line + "\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
                while (running) {
                    val line = readLine(input, MAX_LINE) ?: break
                    when (val d = server.dispatch(line)) {
                        McpServer.Dispatch.None -> Unit
                        is McpServer.Dispatch.Reply -> send(d.line)
                        is McpServer.Dispatch.Call -> calls.execute {
                            val reply = d.run()
                            runCatching { send(reply) }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                open -= sock
                runCatching { sock.close() }
                slots.release()
            }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        const val HELLO_TIMEOUT_MS = 10_000
        const val MAX_HELLO = 4096
        const val MAX_LINE = 4 * 1024 * 1024

        private val random = SecureRandom()

        fun hex(bytes: Int): String {
            val b = ByteArray(bytes)
            random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** `{"dshPhone":1,"token":"…"}` with this launch's token (constant-time compare). */
        fun helloOk(line: String, token: String): Boolean {
            val m = try {
                Json.parse(line) as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return false
            val got = m["token"] as? String ?: return false
            return MessageDigest.isEqual(got.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
        }

        /** One `\n`-terminated UTF-8 line, at most [max] bytes; null at end of stream. */
        fun readLine(input: InputStream, max: Int): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").removeSuffix("\r")
                if (buf.size() >= max) throw java.io.IOException("line too long")
                buf.write(b)
            }
        }

        /** The uid owning the client end of a loopback connection, from /proc/net/tcp{,6}; null if unknown. */
        fun loopbackPeerUid(clientPort: Int, serverPort: Int): Int? {
            for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                val lines = try {
                    File(path).readLines()
                } catch (_: Exception) {
                    continue
                }
                uidFromProcNet(lines, clientPort, serverPort)?.let { return it }
            }
            return null
        }

        /** Parses `/proc/net/tcp` rows: the row whose local port is [clientPort] and remote port [serverPort]. */
        fun uidFromProcNet(lines: List<String>, clientPort: Int, serverPort: Int): Int? {
            for (line in lines.drop(1)) {
                val f = line.trim().split(Regex("\\s+"))
                if (f.size < 8) continue
                val local = f[1].substringAfterLast(':').toIntOrNull(16) ?: continue
                val remote = f[2].substringAfterLast(':').toIntOrNull(16) ?: continue
                if (local == clientPort && remote == serverPort) return f[7].toIntOrNull()
            }
            return null
        }
    }
}
