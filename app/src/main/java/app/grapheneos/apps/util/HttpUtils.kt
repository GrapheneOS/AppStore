package app.grapheneos.apps.util

import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

inline fun openConnection(url: String, configure: HttpURLConnection.() -> Unit): ScopedHttpConnection {
    val connection = URL(url).openConnection() as HttpURLConnection

    connection.apply {
        connectTimeout = 10_000
        readTimeout = 30_000
    }

    connection.configure()
    connection.connect()
    return ScopedHttpConnection(connection)
}

class ScopedHttpConnection(val v: HttpURLConnection) : AutoCloseable {
    override fun close() {
        v.disconnect()
    }
}

fun throwResponseCodeException(conn: HttpURLConnection): Nothing {
    throw ProtocolException("Unexpected HTTP response: ${conn.responseCode} ${conn.responseMessage}, when connecting to ${conn.url}")
}
