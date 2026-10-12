package io.github.devicebridge.android;
import io.github.devicebridge.core.HostServerClient;
import java.util.concurrent.Executor;
/** Models only selection CAS/cleanup. Production Application/Android threading is not under test. */
final class BridgeRuntime {
    static final Executor CLOSERS = Runnable::run;
    private static final BridgeRuntime INSTANCE = new BridgeRuntime();
    private HostServerClient external;
    int replacements, disconnects;
    static BridgeRuntime get() { return INSTANCE; }
    HostServerClient externalServer() { return external; }
    boolean replaceExternalServer(HostServerClient expected, HostServerClient next) {
        replacements++;
        if (external != expected) return false;
        external = next;
        if (expected != null && expected != next) { expected.setListener(null); CLOSERS.execute(expected::close); }
        return true;
    }
    void disconnectAll() { disconnects++; HostServerClient previous = external; external = null; if (previous != null) previous.close(); }
    void setForTest(HostServerClient client) { external = client; }
    void reset() { if (external != null) external.close(); external = null; replacements = 0; disconnects = 0; }
}
