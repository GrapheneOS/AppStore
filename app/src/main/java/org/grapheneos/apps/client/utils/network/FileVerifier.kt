package org.grapheneos.apps.client.utils.network

import org.bouncycastle.crypto.Signer
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
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
    private val publicKey: Ed25519PublicKeyParameters

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
        publicKey = Ed25519PublicKeyParameters(decodedKey, KEY_ID_END)
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
        if (!Arrays.equals(keyId, Arrays.copyOfRange(decodedSignature, ALGORITHM_END, KEY_ID_END))) {
            throw GeneralSecurityException("signature key id does not match public key");
        }
        val signature = Arrays.copyOfRange(decodedSignature, KEY_ID_END, SIGNATURE_SIZE)

        val verifier: Signer = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(message, 0, message.size)

        return verifier.verifySignature(signature)
    }
}
