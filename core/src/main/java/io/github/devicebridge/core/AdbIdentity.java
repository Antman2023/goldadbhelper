package io.github.devicebridge.core;

import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;

/** A new client identity, never imported from the reference application. */
public final class AdbIdentity {
    private final KeyPair pair;
    public AdbIdentity(KeyPair pair) { this.pair = pair; }
    public static AdbIdentity generate() throws GeneralSecurityException {
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return new AdbIdentity(g.generateKeyPair());
    }
    public static AdbIdentity restore(byte[] privatePkcs8, byte[] publicX509) throws GeneralSecurityException {
        KeyFactory f=KeyFactory.getInstance("RSA");
        return new AdbIdentity(new KeyPair(f.generatePublic(new X509EncodedKeySpec(publicX509)),f.generatePrivate(new PKCS8EncodedKeySpec(privatePkcs8))));
    }
    public byte[] privateEncoded() { return pair.getPrivate().getEncoded(); }
    public byte[] publicEncoded() { return pair.getPublic().getEncoded(); }
    public byte[] signToken(byte[] token) throws GeneralSecurityException {
        if(token.length!=20) throw new GeneralSecurityException("Expected 20-byte ADB challenge");
        // AUTH token is already SHA-1: sign the DER DigestInfo without hashing again.
        byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        Signature s=Signature.getInstance("NONEwithRSA"); s.initSign(pair.getPrivate()); s.update(prefix); s.update(token); return s.sign();
    }
    public byte[] adbPublicKey() {
        RSAPublicKey key=(RSAPublicKey)pair.getPublic(); BigInteger n=key.getModulus();
        if(n.bitLength()!=2048) throw new IllegalStateException("ADB requires a 2048-bit RSA key");
        BigInteger two32=BigInteger.ONE.shiftLeft(32);
        int inv=n.mod(two32).modInverse(two32).negate().mod(two32).intValue();
        BigInteger rr=BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(inv).put(little(n,256)).put(little(rr,256)).putInt(key.getPublicExponent().intValue());
        String pub=Base64.getEncoder().encodeToString(b.array())+" devicebridge@android\0";
        return pub.getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] little(BigInteger n,int count) { byte[] be=n.toByteArray(), le=new byte[count]; for(int i=0;i<count && i<be.length;i++)le[i]=be[be.length-1-i];return le; }
    public String fingerprint() throws GeneralSecurityException {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(publicEncoded());
        StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
}
