package com.deepseekharness.app.phone.background

import java.net.InetAddress

/** Keep the address and port from one complete discovery response together. */
internal data class AdbEndpoint(val address: InetAddress, val port: Int) {
    companion object {
        fun discovered(address: InetAddress?, port: Int): AdbEndpoint? =
            if (address != null && port in 1..65535) AdbEndpoint(address, port) else null
    }
}
