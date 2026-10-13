#!/usr/bin/env python3
"""Extract production session selection/cleanup bodies, never rewrite them."""
from pathlib import Path
import sys

source = Path("app/src/main/java/io/github/devicebridge/android/BridgeRuntime.java").read_text(encoding="utf-8")

def method(signature):
    if source.count(signature) != 1:
        raise SystemExit("Expected exactly one production method: " + signature)
    start = source.index(signature)
    brace = source.index("{", start)
    level = 0
    for end in range(brace, len(source)):
        if source[end] == "{":
            level += 1
        elif source[end] == "}":
            level -= 1
            if level == 0:
                return source[start:end + 1]
    raise SystemExit("Unbalanced production method: " + signature)

select = method("public synchronized boolean select(AdbSession session)")
disconnect = method("public boolean disconnect(AdbSession session)")
cleanup = [method(signature) for signature in (
    "public void add(AdbSession s)",
    "public void disconnect(String target)",
    "public void disconnectAll()",
)]
output = Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("""package io.github.devicebridge.android;
import java.util.*;
import java.util.concurrent.Executor;
/** Exact production methods; surrounding state and sessions are memory-only stand-ins. */
final class BridgeRuntimeObjectBindingSubject {
    static final List<Runnable> pendingClosers = new ArrayList<>();
    static final Executor CLOSERS = pendingClosers::add;
    final Map<String, AdbSession> sessions = new LinkedHashMap<>();
    String active;
    HostServerClient externalServer;
    static final class HostServerClient {
        int closes; Object listener = new Object();
        void setListener(Object listener) { this.listener = listener; }
        void close() { closes++; }
    }
    static final class AdbSession {
        final String target; int closes; Runnable closeHook;
        AdbSession(String target) { this.target = target; }
        String target() { return target; }
        void close() { closes++; if (closeHook != null) closeHook.run(); }
    }
""" + select + "\n" + disconnect + "\n" + "\n".join(cleanup) + "\n}\n", encoding="utf-8")
