package org.grapheneos.apps.client.utils.network

import org.bouncycastle.crypto.Signer
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.util.Arrays

class FileVerifier(base64SignifyPublicKey: String) {

    private val keyId: ByteArray
    private val publicKey: ByteArray

    init {
        val decodedKey = Base64.decode(base64SignifyPublicKey)
        if (decodedKey.size != 42) {
            throw GeneralSecurityException("invalid key size")
        }
        val algorithm = String(Arrays.copyOfRange(decodedKey, 0, 2))
        if (algorithm != "Ed") {
            throw GeneralSecurityException("invalid public key algorithm")
        }
        keyId = Arrays.copyOfRange(decodedKey, 2, 10)
        publicKey = Arrays.copyOfRange(decodedKey, 10, 42)
    }

    fun verifySignature(message: ByteArray, base64SignifySignature: String): Boolean {

        val decodedKey = Base64.decode(base64SignifySignature)
        if (decodedKey.size != 74) throw GeneralSecurityException("invalid signature size")

        val algorithm = String(Arrays.copyOfRange(decodedKey, 0, 2))
        if (algorithm != "Ed") {
            throw GeneralSecurityException("invalid public key algorithm. expected Ed but have $algorithm")
        }
        if (BigInteger(1, Arrays.copyOfRange(decodedKey, 2, 10)).toString() != BigInteger(
                1,
                keyId
            ).toString()
        ) {
            throw GeneralSecurityException(
                "invalid key ID. did you sign with the same public key as the one the constructor was called with? the passed keyid is " + BigInteger(
                    1,
                    Arrays.copyOfRange(decodedKey, 2, 10)
                ).toString() + " expected " + BigInteger(1, keyId).toString()
            )
        }
        val signature = Arrays.copyOfRange(decodedKey, 10, 74)

        val pub = Ed25519PublicKeyParameters(publicKey, 0)
        val verifier: Signer = Ed25519Signer()
        verifier.init(false, pub)
        verifier.update(message, 0, message.size)

        return verifier.verifySignature(signature)
    }
}