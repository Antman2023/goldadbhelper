package io.github.devicebridge.core;

import java.io.IOException;

/** A TCP transport which can replace its streams after the ADB STLS exchange. */
public interface TlsUpgradableTransport extends Transport {
    void upgradeToTls(AdbIdentity identity) throws IOException;
    boolean isTls();
    void setReadTimeout(int timeoutMs) throws IOException;
}
