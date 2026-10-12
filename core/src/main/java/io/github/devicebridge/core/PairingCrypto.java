package io.github.devicebridge.core;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import io.github.muntashirakon.crypto.spake2.Spake2Context;
import io.github.muntashirakon.crypto.spake2.Spake2Role;

/** AOSP pairing_auth: SPAKE2, HKDF-SHA256 and sequence-number AES-128-GCM. */
final class PairingCrypto implements Closeable {
    private static final byte[] CLIENT = "adb pair client\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER = "adb pair server\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8);
    private final Spake2Context spake;
    private final byte[] message;
    private byte[] key;
    private long encryptSequence, decryptSequence;
    private boolean closed;

    PairingCrypto(boolean client, byte[] password) {
        spake = new Spake2Context(client ? Spake2Role.Alice : Spake2Role.Bob, client ? CLIENT : SERVER, client ? SERVER : CLIENT);
        message = spake.generateMessage(password);
    }
    byte[] message() { return message.clone(); }
    void initialize(byte[] peerMessage) throws GeneralSecurityException {
        if (closed || key != null) throw new GeneralSecurityException("Pairing context already used");
        byte[] secret = null, prk = null, expanded = null;
        try {
            secret = spake.processMessage(peerMessage);
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            prk = hmac.doFinal(secret);
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(INFO); expanded = hmac.doFinal(new byte[]{1});
            key = Arrays.copyOf(expanded, 16);
        } catch (IllegalArgumentException | IllegalStateException e) { throw new GeneralSecurityException("Invalid SPAKE2 message", e); }
        finally { wipe(secret); wipe(prk); wipe(expanded); spake.destroy(); }
    }
    byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] out = crypt(Cipher.ENCRYPT_MODE, plain, encryptSequence); ++encryptSequence; return out;
    }
    byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        byte[] out = crypt(Cipher.DECRYPT_MODE, encrypted, decryptSequence); ++decryptSequence; return out;
    }
    private byte[] crypt(int mode, byte[] data, long sequence) throws GeneralSecurityException {
        if (closed || key == null || sequence < 0) throw new GeneralSecurityException("Pairing cipher unavailable");
        byte[] nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array();
        // KitKat's system BouncyCastle does not accept GCMParameterSpec. Use the same
        // bundled, pinned provider as ADB TLS; never fall back to a weaker cipher/mode.
        Cipher cipher = AndroidFileAccess.isAndroid()
                ? Cipher.getInstance("AES/GCM/NoPadding", TlsSupport.provider())
                : Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(data);
    }
    public void close() { closed = true; wipe(key); wipe(message); spake.destroy(); }
    private static void wipe(byte[] data) { if (data != null) Arrays.fill(data, (byte) 0); }
}
