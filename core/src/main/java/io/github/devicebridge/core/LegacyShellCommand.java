package io.github.devicebridge.core;
import java.io.IOException;
import java.util.UUID;

/** Explicit one-shot status framing for old adbd, without pretending shell-v1 has exit packets. */
public final class LegacyShellCommand {
    private final String marker = "GB_STATUS_" + UUID.randomUUID().toString().replace("-", "");
    public static final class Reply {
        public final String output;
        public final int exitCode;
        Reply(String output, int exitCode) { this.output = output; this.exitCode = exitCode; }
    }
    public String wrap(String command) {
        // KitKat's /system/bin/sh may not ship printf. Only fixed ASCII marker and numeric status go to echo.
        return "sh -c " + DeviceCommands.quote(command) + "; _gb_status=$?; echo; echo \"" + marker + ":$_gb_status\"";
    }
    public Reply parse(String text) throws IOException {
        String clean = text.replace("\r\n", "\n");
        int start = clean.lastIndexOf("\n" + marker + ":");
        if (start < 0) throw new IOException("Legacy command ended without a verified exit acknowledgement");
        String tail = clean.substring(start + marker.length() + 2);
        if (!tail.matches("[0-9]{1,3}\\n")) throw new IOException("Invalid legacy exit acknowledgement");
        int status = Integer.parseInt(tail.trim());
        if (status > 255) throw new IOException("Invalid legacy exit status");
        return new Reply(clean.substring(0, start), status);
    }
}
