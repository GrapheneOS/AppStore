package org.grapheneos.apps.client;

import org.bouncycastle.crypto.Signer;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;

import java.math.BigInteger;
import java.util.Arrays;

public class fileverifier {
    private byte[] keyId;
    private byte[] publicKey;

    public fileverifier(String base64SignifyPublicKey) {
        byte[] decodedKey = Base64.decode(base64SignifyPublicKey);
        if (decodedKey.length != 42) {
            throw new SecurityException("invalid key size");
        }
        String algorithm = new String(Arrays.copyOfRange(decodedKey, 0, 2));
        if (!algorithm.equals("Ed")) {
            throw new SecurityException("invalid public key algorithm");
        }
        this.keyId = Arrays.copyOfRange(decodedKey, 2, 10);
        this.publicKey = Arrays.copyOfRange(decodedKey, 10, 42);
    }

    private static boolean internalVerifySignature(byte[] pubkey, byte[] signature, byte[] message) {
        Ed25519PublicKeyParameters publicKey = new Ed25519PublicKeyParameters(pubkey, 0);
        Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    public boolean verifySignature(byte[] message, String base64SignifySignature) {
        byte[] decodedKey = Base64.decode(base64SignifySignature);
        if (decodedKey.length != 74) {
            throw new SecurityException("invalid signature size");
        }
        String algorithm = new String(Arrays.copyOfRange(decodedKey, 0, 2));
        if (!algorithm.equals("Ed")) {
            throw new SecurityException("invalid public key algorithm. expected Ed but have " + algorithm);
        }
        if (!new BigInteger(1, Arrays.copyOfRange(decodedKey, 2, 10)).toString().equals(new BigInteger(1, this.keyId).toString())) {
            throw new SecurityException("invalid key ID. did you sign with the same public key as the one the constructor was called with? the passed keyid is " + new BigInteger(1, Arrays.copyOfRange(decodedKey, 2, 10)).toString() + " expected " + new BigInteger(1, this.keyId).toString());
        }
        byte[] signature = Arrays.copyOfRange(decodedKey, 10, 74);
        return internalVerifySignature(this.publicKey, signature, message);
    }

}