package io.github.devicebridge.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Complete packets delivered to an in-memory peer, independent of the sender's cancellation. */
final class SyntheticAdbPackets {
    private SyntheticAdbPackets() { }
    static AdbPacket decode(byte[] bytes, int offset, int length) throws IOException {
        // Real peers decode on their own thread. These transport fixtures run inline
        // on the sender instead, including when it sends CLSE during cancellation.
        // Preserve that thread's flag while separating peer decoding from live I/O.
        boolean interrupted = Thread.interrupted();
        try { return AdbPacket.read(new ByteArrayInputStream(bytes, offset, length), AdbPacket.MAX_PAYLOAD, true); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
