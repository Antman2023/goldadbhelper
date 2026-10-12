package io.github.devicebridge.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Incremental UTF-8 to plain terminal text. Use a separate instance for each stdout/stderr stream.
 * Decoder buffers are fixed size; escape/control-string payloads are discarded without buffering.
 * Returned text is at most proportional to the caller's input. The UI must bound its scrollback.
 * LF, CR and tab are preserved. ANSI CSI, OSC (including hyperlinks/clipboard), DCS, other escape
 * sequences and unsafe controls are stripped, even when split across packets. This is a plain-text
 * transcript, not a VT screen: cursor movement, erasure, backspace and carriage-return overwrite
 * are not emulated. For cursor-aware interactive applications, use a terminal emulator instead.
 */
public final class TerminalOutputDecoder {
    private static final int TEXT = 0, ESC = 1, INTERMEDIATE = 2, CSI = 3, STRING = 4, STRING_ESC = 5;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer bytes = ByteBuffer.allocate(4096);
    private final CharBuffer chars = CharBuffer.allocate(4096);
    private int state = TEXT;
    private boolean osc;
    private boolean finished;

    public synchronized String accept(byte[] data) {
        if (data == null) throw new NullPointerException("data");
        return accept(data, 0, data.length);
    }

    public synchronized String accept(byte[] data, int offset, int length) {
        if (data == null) throw new NullPointerException("data");
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
        if (finished) throw new IllegalStateException("Terminal decoder is finished");
        StringBuilder text = new StringBuilder(Math.min(length, 4096));
        while (length > 0) {
            int n = Math.min(length, bytes.remaining());
            bytes.put(data, offset, n);
            offset += n;
            length -= n;
            decode(false, text);
        }
        return text.toString();
    }

    /** Flushes any incomplete UTF-8 as a replacement character. Repeated finish calls are empty. */
    public synchronized String finish() {
        if (finished) return "";
        finished = true;
        StringBuilder text = new StringBuilder();
        decode(true, text);
        CoderResult result;
        do {
            result = decoder.flush(chars);
            drain(text);
        } while (result.isOverflow());
        state = TEXT;
        return text.toString();
    }

    private void decode(boolean end, StringBuilder text) {
        bytes.flip();
        CoderResult result;
        do {
            result = decoder.decode(bytes, chars, end);
            drain(text);
        } while (result.isOverflow());
        bytes.compact();
    }

    private void drain(StringBuilder text) {
        chars.flip();
        while (chars.hasRemaining()) filter(chars.get(), text);
        chars.clear();
    }

    private void filter(char c, StringBuilder text) {
        if (state == STRING || state == STRING_ESC) {
            if (c == '\u009c' || (osc && c == '\u0007') || (state == STRING_ESC && c == '\\')) {
                state = TEXT;
            } else if (c == '\u0018' || c == '\u001a') {
                state = TEXT;
            } else {
                state = c == '\u001b' ? STRING_ESC : STRING;
            }
            return;
        }
        if (c == '\u001b') { state = ESC; return; }
        if (c == '\u0018' || c == '\u001a') { state = TEXT; return; }
        if (c == '\u009b') { state = CSI; return; }
        if (c == '\u009d' || c == '\u0090' || c == '\u0098' || c == '\u009e' || c == '\u009f') {
            osc = c == '\u009d'; state = STRING; return;
        }
        // Execute only transcript-compatible C0 controls; never expose raw escape/control bytes.
        if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) {
            if (c == '\n' || c == '\r' || c == '\t') text.append(c);
            return;
        }
        if (state == ESC) {
            if (c == '[') state = CSI;
            else if (c == ']' || c == 'P' || c == 'X' || c == '^' || c == '_') {
                osc = c == ']'; state = STRING;
            } else if (c >= 0x20 && c <= 0x2f) state = INTERMEDIATE;
            else {
                state = TEXT;
                if (c > 0x7e) text.append(c);
            }
            return;
        }
        if (state == INTERMEDIATE) {
            if (c >= 0x20 && c <= 0x2f) return;
            state = TEXT;
            if (c > 0x7e) text.append(c);
            return;
        }
        if (state == CSI) {
            if (c >= 0x40 && c <= 0x7e) state = TEXT;
            else if (c < 0x20 || c > 0x3f) { state = TEXT; text.append(c); }
            return;
        }
        text.append(c);
    }
}
