package io.github.devicebridge.core;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded, styled transcript, not a VT screen or a command-completion detector.
 * Input has already passed through TerminalOutputDecoder. Prompt highlighting is presentation
 * only: it recognizes common Android prompt prefixes in actual output, never invents a prompt,
 * runs a command, interprets an OSC, or decides that a user command has finished.
 */
public final class TerminalTranscript {
    public static final int NORMAL = 0, ERROR = 1, PROMPT = 2;
    public static final int SCROLLBACK_LIMIT = 1024 * 1024;
    public static final int PENDING_LIMIT = 64 * 1024;
    private static final int PROMPT_SCAN_LIMIT = 4096;
    private static final Pattern ANDROID_PROMPT = Pattern.compile(
            "(?:[0-9]+\\|)?(?:[A-Za-z0-9_.-]+@)?[A-Za-z0-9_.-]+:[^\\r\\n]*? ?[#$](?: |$)");

    /** Immutable output batch. Styles are per UTF-16 unit and bounded by the text limit. */
    public static final class Text {
        public final String text;
        private final byte[] styles;

        private Text(String text, byte[] styles) { this.text = text; this.styles = styles; }
        public int styleAt(int index) { return styles[index]; }
        public int length() { return text.length(); }
        public static Text plain(String text, int style) {
            checkStyle(style);
            byte[] styles = new byte[text.length()];
            Arrays.fill(styles, (byte) style);
            return new Text(text, styles);
        }
    }

    /** Incremental edit, applied in order: remove prefix, then replace [replaceFrom, end). */
    public static final class Change {
        public final int removedPrefix, replaceFrom;
        public final Text replacement;
        private Change(int removedPrefix, int replaceFrom, Text replacement) {
            this.removedPrefix = removedPrefix;
            this.replaceFrom = replaceFrom;
            this.replacement = replacement;
        }
    }

    /** Fixed-size pending queue. Drain and clear are atomic with respect to reader callbacks. */
    public static final class Pending {
        private final StringBuilder text = new StringBuilder();
        private final byte[] styles = new byte[PENDING_LIMIT];
        private boolean dropped;

        public synchronized void append(String value, int style) {
            checkStyle(style);
            if (value.isEmpty()) return;
            int offset = Math.max(0, value.length() - PENDING_LIMIT);
            offset = safeStart(value, offset);
            int count = value.length() - offset;
            int trim = Math.max(0, text.length() + count - PENDING_LIMIT);
            trim = safeStart(text, trim);
            if (offset > 0 || trim > 0) dropped = true;
            if (trim > 0) {
                System.arraycopy(styles, trim, styles, 0, text.length() - trim);
                text.delete(0, trim);
            }
            int start = text.length();
            text.append(value, offset, value.length());
            Arrays.fill(styles, start, text.length(), (byte) style);
        }

        public synchronized Text drain() {
            String notice = dropped ? "\n[输出过快，较早待显示内容已截断]\n" : "";
            byte[] result = new byte[notice.length() + text.length()];
            Arrays.fill(result, 0, notice.length(), (byte) ERROR);
            System.arraycopy(styles, 0, result, notice.length(), text.length());
            Text batch = new Text(notice + text.toString(), result);
            text.setLength(0);
            dropped = false;
            return batch;
        }

        public synchronized int length() { return text.length(); }
    }

    private final int limit;
    private final StringBuilder text = new StringBuilder();
    private final byte[] styles;
    private boolean firstLinePartial;

    public TerminalTranscript() { this(SCROLLBACK_LIMIT); }
    /** Small explicit limits are useful for host-only tests. */
    public TerminalTranscript(int limit) {
        if (limit < 2 || limit > SCROLLBACK_LIMIT) throw new IllegalArgumentException("Invalid transcript limit");
        this.limit = limit;
        styles = new byte[limit];
    }

    public Change append(Text batch) {
        int oldLength = text.length();
        int source = safeStart(batch.text, Math.max(0, batch.length() - limit));
        int count = batch.length() - source;
        int trim = oldLength + count > limit ? Math.max(oldLength + count - limit, oldLength / 2) : 0;
        trim = safeStart(text, trim);
        if (trim > 0) {
            firstLinePartial = text.charAt(trim - 1) != '\n';
            System.arraycopy(styles, trim, styles, 0, oldLength - trim);
            text.delete(0, trim);
        }
        if (source > 0) firstLinePartial = batch.text.charAt(source - 1) != '\n';
        int start = text.length();
        int replaceFrom = lastLineStart(text, start);
        // A huge line cannot be a recognized prompt. Do not copy/restyle it on every flush.
        if (start - replaceFrom > PROMPT_SCAN_LIMIT) replaceFrom = start;
        text.append(batch.text, source, batch.length());
        System.arraycopy(batch.styles, source, styles, start, count);
        highlightPrompts(replaceFrom);
        return new Change(trim, replaceFrom, slice(replaceFrom, text.length()));
    }

    /** Match the original clear action: keep the actual last line, never a cached older prompt. */
    public Change clearKeepingLastLine() {
        int oldLength = text.length(), end = oldLength;
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        int start = lastLineStart(text, end);
        Text tail = slice(start, end);
        boolean partial = start == 0 && firstLinePartial;
        text.setLength(0);
        text.append(tail.text);
        System.arraycopy(tail.styles, 0, styles, 0, tail.length());
        firstLinePartial = partial;
        return new Change(oldLength, 0, tail);
    }

    public Change reset() {
        int oldLength = text.length();
        text.setLength(0);
        firstLinePartial = false;
        return new Change(oldLength, 0, Text.plain("", NORMAL));
    }

    public Text snapshot() { return slice(0, text.length()); }
    public int length() { return text.length(); }
    public String text() { return text.toString(); }
    public int styleAt(int index) {
        if (index < 0 || index >= text.length()) throw new IndexOutOfBoundsException();
        return styles[index];
    }

    private void highlightPrompts(int from) {
        int line = from;
        // 'from' can be the middle of an overlong line; only inspect its following lines.
        if (line > 0 && text.charAt(line - 1) != '\n') {
            int newline = text.indexOf("\n", line);
            if (newline < 0) return;
            line = newline + 1;
        }
        while (line < text.length()) {
            int end = text.indexOf("\n", line);
            if (end < 0) end = text.length();
            int scanEnd = Math.min(end, line + PROMPT_SCAN_LIMIT);
            for (int i = line; i < scanEnd; i++) if (styles[i] == PROMPT) styles[i] = NORMAL;
            if (!(line == 0 && firstLinePartial)) {
                Matcher match = ANDROID_PROMPT.matcher(text);
                match.region(line, scanEnd);
                if (match.lookingAt()) {
                    int promptEnd = match.end();
                    // An artificial scan boundary is not a real end of prompt.
                    boolean boundary = promptEnd < scanEnd || scanEnd == end || text.charAt(promptEnd - 1) == ' ';
                    boolean plain = true;
                    for (int i = line; i < promptEnd; i++) if (styles[i] == ERROR) { plain = false; break; }
                    if (boundary && plain) Arrays.fill(styles, line, promptEnd, (byte) PROMPT);
                }
            }
            line = end + 1;
        }
    }

    private Text slice(int from, int to) {
        return new Text(text.substring(from, to), Arrays.copyOfRange(styles, from, to));
    }

    private static int lastLineStart(CharSequence value, int end) {
        for (int i = end - 1; i >= 0; i--) if (value.charAt(i) == '\n') return i + 1;
        return 0;
    }

    private static int safeStart(CharSequence value, int offset) {
        if (offset > 0 && offset < value.length() && Character.isLowSurrogate(value.charAt(offset)) &&
                Character.isHighSurrogate(value.charAt(offset - 1))) return offset + 1;
        return offset;
    }

    private static void checkStyle(int style) {
        if (style < NORMAL || style > PROMPT) throw new IllegalArgumentException("Invalid terminal style");
    }

    public static String endedText(int code, String error) {
        if (error != null) return "\n[终端连接错误] " + error + "\n输入命令可重新开始。\n";
        return "\n[终端已结束" + (code < 0 ? "，退出码未知" : "，退出码：" + code) + "]\n输入命令可重新开始。\n";
    }

    public static int endedStyle(int code, String error) { return error != null || code > 0 ? ERROR : NORMAL; }
}
