package org.grapheneos.apps.client.utils.network

import org.bouncycastle.crypto.Signer
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.util.Arrays

// Signify binary public key format (42 bytes):
// 2 byte algorithm
// 8 byte key id
// 32 byte Ed25519 key
//
// Signify binary signature format (74 bytes):
// 2 byte algorithm
// 8 byte key id
// 64 byte Ed25519 signature

const val PUBLIC_KEY_SIZE = 42
const val SIGNATURE_SIZE = 74

const val ALGORITHM_END = 2
const val KEY_ID_END = 10

const val ALGORITHM = "Ed"

class FileVerifier(base64SignifyPublicKey: String) {

    private val keyId: ByteArray
    private val publicKey: ByteArray

    init {
        val decodedKey = Base64.decode(base64SignifyPublicKey)
        if (decodedKey.size != PUBLIC_KEY_SIZE) {
            throw GeneralSecurityException("Invalid key size")
        }
        val algorithm = String(Arrays.copyOfRange(decodedKey, 0, ALGORITHM_END))
        if (algorithm != ALGORITHM) {
            throw GeneralSecurityException("Invalid public key algorithm")
        }
        keyId = Arrays.copyOfRange(decodedKey, ALGORITHM_END, KEY_ID_END)
        publicKey = Arrays.copyOfRange(decodedKey, KEY_ID_END, PUBLIC_KEY_SIZE)
    }

    fun verifySignature(message: ByteArray, base64SignifySignature: String): Boolean {
        val decodedSignature = Base64.decode(base64SignifySignature)
        if (decodedSignature.size != SIGNATURE_SIZE) {
            throw GeneralSecurityException("invalid signature size")
        }

        val algorithm = String(Arrays.copyOfRange(decodedSignature, 0, ALGORITHM_END))
        if (algorithm != ALGORITHM) {
            throw GeneralSecurityException("Invalid public key algorithm. Expected \"Ed\" but got \"$algorithm\"")
        }
        if (BigInteger(1, Arrays.copyOfRange(decodedSignature, ALGORITHM_END, KEY_ID_END)).toString() != BigInteger(
                1,
                keyId
            ).toString()
        ) {
            throw GeneralSecurityException(
                "Invalid key ID. Did you sign with the same public key as the one the constructor was called with? The passed key id is " + BigInteger(
                    1,
                    Arrays.copyOfRange(decodedSignature, ALGORITHM_END, KEY_ID_END)
                ).toString() + ", but expected " + BigInteger(1, keyId).toString()
            )
        }
        val signature = Arrays.copyOfRange(decodedSignature, KEY_ID_END, SIGNATURE_SIZE)

        val pub = Ed25519PublicKeyParameters(publicKey, 0)
        val verifier: Signer = Ed25519Signer()
        verifier.init(false, pub)
        verifier.update(message, 0, message.size)

        return verifier.verifySignature(signature)
    }
}
