#!/usr/bin/env python3
"""Extract only the production object-bound select/disconnect bodies, never rewrite them."""
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
disconnect = method("public synchronized boolean disconnect(AdbSession session)")
output = Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("""package io.github.devicebridge.android;
import java.util.*;
import java.util.concurrent.Executor;
/** Exact two production methods; all surrounding state and sessions are memory-only stand-ins. */
final class BridgeRuntimeObjectBindingSubject {
    static final List<Runnable> pendingClosers = new ArrayList<>();
    static final Executor CLOSERS = pendingClosers::add;
    final Map<String, AdbSession> sessions = new LinkedHashMap<>();
    String active;
    static final class AdbSession {
        final String target; int closes;
        AdbSession(String target) { this.target = target; }
        String target() { return target; }
        void close() { closes++; }
    }
""" + select + "\n" + disconnect + "\n}\n", encoding="utf-8")
