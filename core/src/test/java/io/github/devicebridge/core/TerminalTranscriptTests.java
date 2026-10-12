package io.github.devicebridge.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Host-only transcript fixtures. Does not connect to an Android device or run the original APK. */
public final class TerminalTranscriptTests {
    private static int assertions;

    public static void main(String[] args) {
        promptAndOutputColors();
        fragmentedPrompts();
        clearUsesCurrentTail();
        pendingPreservesStreams();
        boundedQueuesAndScrollback();
        controlStringsRemainInert();
        endedMessages();
        randomizedIncrementalEdits();
        System.out.println("TerminalTranscriptTests: " + assertions + " assertions passed (host-only transcript fixtures; no Android rendering/device verification)");
    }

    private static void promptAndOutputColors() {
        TerminalTranscript transcript = new TerminalTranscript();
        String first = "shell@generic:/ $ ";
        append(transcript, first, TerminalTranscript.NORMAL);
        styles(transcript, 0, first.length(), TerminalTranscript.PROMPT, "original screenshot prompt is green");
        append(transcript, "echo hello\r\nhello\r\n", TerminalTranscript.NORMAL);
        styles(transcript, first.length(), transcript.length(), TerminalTranscript.NORMAL, "echo and stdout use theme color");
        int failure = transcript.length();
        append(transcript, "permission denied\n", TerminalTranscript.ERROR);
        styles(transcript, failure, transcript.length(), TerminalTranscript.ERROR, "known stderr remains red");
        int next = transcript.length();
        String nativePrompt = "127|generic_x86:/data/local/tmp # ";
        append(transcript, nativePrompt, TerminalTranscript.NORMAL);
        styles(transcript, next, transcript.length(), TerminalTranscript.PROMPT, "real target cwd and prompt retained");
        equal(first + "echo hello\r\nhello\r\npermission denied\n" + nativePrompt, transcript.text(), "no invented prompt or exit result");
        TerminalTranscript errors = new TerminalTranscript();
        append(errors, first, TerminalTranscript.ERROR);
        styles(errors, 0, errors.length(), TerminalTranscript.ERROR, "prompt-like stderr never overrides error color");
        TerminalTranscript plain = new TerminalTranscript();
        append(plain, "price $ 10\n# heading\n$ just text\n", TerminalTranscript.NORMAL);
        styles(plain, 0, plain.length(), TerminalTranscript.NORMAL, "ordinary symbols are not Android prompts");
    }

    private static void fragmentedPrompts() {
        String text = "shell@generic:/ $ cd /data/local/tmp\r\nroot@generic:/data/local/tmp # echo 中文🙂\r\n中文🙂\r\ngeneric_x86:/ # ";
        byte[] wire = text.getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= wire.length; split++) {
            TerminalOutputDecoder decoder = new TerminalOutputDecoder();
            TerminalTranscript transcript = new TerminalTranscript();
            append(transcript, decoder.accept(wire, 0, split), TerminalTranscript.NORMAL);
            append(transcript, decoder.accept(wire, split, wire.length - split) + decoder.finish(), TerminalTranscript.NORMAL);
            equal(text, transcript.text(), "fragmented UTF-8 transcript");
            check(transcript.styleAt(0) == TerminalTranscript.PROMPT, "initial prompt survives fragmentation");
            check(transcript.styleAt(text.indexOf("cd /")) == TerminalTranscript.NORMAL, "echo is not prompt color");
            check(transcript.styleAt(text.indexOf("root@")) == TerminalTranscript.PROMPT, "completed coalesced prompt styled");
            check(transcript.styleAt(text.lastIndexOf("generic_x86")) == TerminalTranscript.PROMPT, "current prompt styled");
        }
        TerminalTranscript transcript = new TerminalTranscript();
        for (char c : "shell@generic:/ $".toCharArray()) append(transcript, String.valueOf(c), TerminalTranscript.NORMAL);
        check(transcript.styleAt(0) == TerminalTranscript.PROMPT, "prompt without final space recognized");
        append(transcript, "price", TerminalTranscript.NORMAL);
        styles(transcript, 0, transcript.length(), TerminalTranscript.NORMAL, "partial match can be undone, never a completion signal");
    }

    private static void clearUsesCurrentTail() {
        TerminalTranscript transcript = new TerminalTranscript();
        append(transcript, "generic_x86:/ # cd /data/local/tmp\nroot@generic:/data/local/tmp # ", TerminalTranscript.NORMAL);
        transcript.clearKeepingLastLine();
        equal("root@generic:/data/local/tmp # ", transcript.text(), "clear keeps current target prompt and cwd");
        styles(transcript, 0, transcript.length(), TerminalTranscript.PROMPT, "clear preserves prompt styling");
        append(transcript, "long_running_job\nworking...", TerminalTranscript.NORMAL);
        transcript.clearKeepingLastLine();
        equal("working...", transcript.text(), "running output cannot resurrect a stale prompt");
        append(transcript, "\r\n\r\n", TerminalTranscript.NORMAL);
        transcript.clearKeepingLastLine();
        equal("working...", transcript.text(), "trailing empty lines handled like original split without crash");
        transcript.reset();
        transcript.clearKeepingLastLine();
        equal("", transcript.text(), "empty clear is safe");
        append(transcript, "\n\r\n", TerminalTranscript.NORMAL);
        transcript.clearKeepingLastLine();
        equal("", transcript.text(), "all-empty clear is safe");

        TerminalTranscript.Pending pending = new TerminalTranscript.Pending();
        pending.append("old output\nroot@generic:/new # ", TerminalTranscript.NORMAL);
        transcript.append(pending.drain()); // The UI drains on clear before retaining the last line.
        transcript.clearKeepingLastLine();
        equal(0, pending.drain().length(), "pre-clear queued output cannot reappear");
        equal("root@generic:/new # ", transcript.text(), "clear considers coalesced current prompt");
        pending.append("echo next\nnext\nroot@generic:/new # ", TerminalTranscript.NORMAL);
        transcript.append(pending.drain());
        check(transcript.text().endsWith("next\nroot@generic:/new # "), "new post-clear output remains visible");
    }

    private static void pendingPreservesStreams() {
        TerminalTranscript.Pending pending = new TerminalTranscript.Pending();
        TerminalOutputDecoder stdout = new TerminalOutputDecoder(), stderr = new TerminalOutputDecoder();
        byte[] han = "中".getBytes(StandardCharsets.UTF_8);
        pending.append(stdout.accept(han, 0, 1), TerminalTranscript.NORMAL);
        pending.append(stderr.accept("error".getBytes(StandardCharsets.UTF_8)), TerminalTranscript.ERROR);
        pending.append(stdout.accept(han, 1, 2), TerminalTranscript.NORMAL);
        TerminalTranscript.Text batch = pending.drain();
        equal("error中", batch.text, "streams decode independently and preserve displayed arrival order");
        for (int i = 0; i < 5; i++) equal(TerminalTranscript.ERROR, batch.styleAt(i), "stderr role survives coalescing");
        equal(TerminalTranscript.NORMAL, batch.styleAt(5), "stdout role survives coalescing");
        equal(0, pending.length(), "queue emptied");
        equal(0, pending.drain().length(), "empty drain is idempotent");
    }

    private static void boundedQueuesAndScrollback() {
        TerminalTranscript.Pending pending = new TerminalTranscript.Pending();
        String tail = "🙂" + repeat('y', TerminalTranscript.PENDING_LIMIT - 1);
        pending.append("old", TerminalTranscript.NORMAL);
        pending.append(tail, TerminalTranscript.ERROR);
        check(pending.length() <= TerminalTranscript.PENDING_LIMIT, "pending stays at 64K UTF-16 units");
        TerminalTranscript.Text batch = pending.drain();
        check(batch.text.contains("较早待显示内容已截断"), "truncation is visible");
        check(batch.text.endsWith(repeat('y', TerminalTranscript.PENDING_LIMIT - 1)), "latest output retained");
        check(batch.text.indexOf('\ud83d') < 0 && batch.text.indexOf('\ude42') < 0, "pending trim does not split a surrogate pair");
        for (int i = 0; i < 200; i++) pending.append(repeat('z', 8192), i % 2);
        check(pending.length() <= TerminalTranscript.PENDING_LIMIT, "sustained output remains bounded");
        TerminalTranscript transcript = new TerminalTranscript();
        for (int i = 0; i < 150; i++) transcript.append(TerminalTranscript.Text.plain(repeat('x', 8192), TerminalTranscript.NORMAL));
        check(transcript.length() <= TerminalTranscript.SCROLLBACK_LIMIT, "scrollback remains at 1M UTF-16 units");
        append(transcript, "\nroot@generic:/latest # ", TerminalTranscript.NORMAL);
        check(transcript.styleAt(transcript.length() - 1) == TerminalTranscript.PROMPT, "prompt after overlong line still recognized");
        TerminalTranscript small = new TerminalTranscript(16);
        append(small, "🙂12345678901234", TerminalTranscript.NORMAL);
        TerminalTranscript.Change change = small.append(TerminalTranscript.Text.plain("abcdefghij", TerminalTranscript.ERROR));
        check(small.length() <= 16, "small scrollback cap strict");
        check(!Character.isLowSurrogate(small.text().charAt(0)), "scrollback prefix remains well-formed UTF-16");
        check(change.removedPrefix > 0, "incremental edit exposes prefix trim");
        append(small, repeat('n', 100), TerminalTranscript.NORMAL);
        equal(16, small.length(), "oversized direct batch is bounded");
    }

    private static void controlStringsRemainInert() {
        String wire = "\u001b[31mstdout\u001b[0m\n\u001b]52;c;secret clipboard\u0007\u001b]8;;https://example.invalid\u001b\\link\u001b]8;;\u001b\\\nroot@generic:/ # ";
        TerminalOutputDecoder decoder = new TerminalOutputDecoder();
        TerminalTranscript transcript = new TerminalTranscript();
        for (byte value : wire.getBytes(StandardCharsets.UTF_8)) append(transcript, decoder.accept(new byte[]{value}), TerminalTranscript.NORMAL);
        append(transcript, decoder.finish(), TerminalTranscript.NORMAL);
        equal("stdout\nlink\nroot@generic:/ # ", transcript.text(), "ANSI, OSC clipboard and hyperlinks remain inert");
        equal(TerminalTranscript.NORMAL, transcript.styleAt(0), "ANSI SGR is deliberately not the source of stream color");
        equal(TerminalTranscript.PROMPT, transcript.styleAt(transcript.length() - 1), "prompt survives filtered control strings");
    }

    private static void endedMessages() {
        equal(TerminalTranscript.NORMAL, TerminalTranscript.endedStyle(0, null), "successful shell exit neutral");
        equal(TerminalTranscript.ERROR, TerminalTranscript.endedStyle(7, null), "nonzero shell exit red");
        equal(TerminalTranscript.ERROR, TerminalTranscript.endedStyle(-1, "connection lost"), "transport error red");
        check(TerminalTranscript.endedText(-1, null).contains("退出码未知"), "legacy unknown is never success");
        check(!TerminalTranscript.endedText(-1, null).contains("退出码：-1"), "legacy sentinel not exposed as actual code");
        check(TerminalTranscript.endedText(7, null).contains("退出码：7"), "actual exit status shown");
        check(TerminalTranscript.endedText(0, null).contains("输入命令可重新开始"), "restart affordance present");
    }

    private static void randomizedIncrementalEdits() {
        Random random = new Random(12206L);
        TerminalTranscript transcript = new TerminalTranscript(128);
        StringBuilder view = new StringBuilder();
        byte[] viewStyles = new byte[0];
        String[] pieces = {"foo", "\n", "🙂", "shell@generic:/", " $ ", "\r\n", "中文", "0|root@host:/a b # ", repeat('x', 90)};
        for (int i = 0; i < 2000; i++) {
            TerminalTranscript.Change change;
            int action = random.nextInt(20);
            if (action == 0) change = transcript.reset();
            else if (action == 1) change = transcript.clearKeepingLastLine();
            else change = transcript.append(TerminalTranscript.Text.plain(pieces[random.nextInt(pieces.length)], random.nextInt(2)));
            view.delete(0, change.removedPrefix);
            viewStyles = Arrays.copyOfRange(viewStyles, change.removedPrefix, viewStyles.length);
            view.replace(change.replaceFrom, view.length(), change.replacement.text);
            viewStyles = Arrays.copyOf(viewStyles, view.length());
            for (int j = 0; j < change.replacement.length(); j++) viewStyles[change.replaceFrom + j] = (byte) change.replacement.styleAt(j);
            equal(transcript.text(), view.toString(), "incremental text patch matches model");
            check(view.length() <= 128, "incremental view cap");
            for (int j = 0; j < view.length(); j++) equal(transcript.styleAt(j), viewStyles[j], "incremental style patch matches model");
        }
    }

    private static void append(TerminalTranscript transcript, String text, int style) { transcript.append(TerminalTranscript.Text.plain(text, style)); }
    private static String repeat(char value, int count) { char[] data = new char[count]; Arrays.fill(data, value); return new String(data); }
    private static void styles(TerminalTranscript transcript, int from, int to, int style, String message) { for (int i = from; i < to; i++) equal(style, transcript.styleAt(i), message); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { assertions++; if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", actual " + actual); }
    private static void equal(int expected, int actual, String message) { assertions++; if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", actual " + actual); }
}
