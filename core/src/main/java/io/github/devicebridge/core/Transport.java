package io.github.devicebridge.core;

import java.io.*;

/** A cancellable byte stream; close() must unblock an in-progress read. */
public interface Transport extends Closeable {
    InputStream input() throws IOException;
    OutputStream output() throws IOException;
    String description();
    default void setReadTimeout(int timeoutMs) throws IOException {}
}
