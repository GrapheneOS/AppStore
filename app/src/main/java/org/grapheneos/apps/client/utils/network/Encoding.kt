package org.grapheneos.apps.client.utils.network

sealed class Encoding(open val name: String, open val code: String) {
    data class Gzip(
        override val name: String = "Gzip",
        override val code: String = "gzip"
    ) : Encoding(name, code)

    data class Brotli(
        override val name: String = "Brotli",
        override val code: String = "br"
    ) : Encoding(name, code)

    data class Uncompressed(
        override val name: String = "Uncompressed",
        override val code: String = "identity"
    ) : Encoding(name, code)
}