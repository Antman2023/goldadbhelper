package io.github.devicebridge.android;

import android.os.Handler;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import io.github.devicebridge.core.InteractiveShell;
import io.github.devicebridge.core.TerminalOutputDecoder;
import io.github.devicebridge.core.TerminalTranscript;

/** Android span adapter. All view/model edits run on the UI thread; no device I/O is done here. */
final class TerminalRenderer {
    static final int PROMPT_COLOR = 0xff3aa23a, ERROR_COLOR = 0xffff0000;
    private final TextView view;
    private final TerminalTranscript transcript = new TerminalTranscript();

    TerminalRenderer(TextView view) {
        this.view = view;
        view.setTextColor(Ui.INK);
        view.setText("", TextView.BufferType.EDITABLE);
    }

    void append(TerminalTranscript.Text text) {
        if (text.length() > 0) apply(transcript.append(text));
    }

    void clearKeepingLastLine() { apply(transcript.clearKeepingLastLine()); }
    void reset() { apply(transcript.reset()); }
    TerminalTranscript.Text snapshot() { return transcript.snapshot(); }

    private void apply(TerminalTranscript.Change change) {
        Editable buffer = (Editable) view.getText();
        if (change.removedPrefix > 0) buffer.delete(0, change.removedPrefix);
        SpannableStringBuilder replacement = new SpannableStringBuilder(change.replacement.text);
        for (int start = 0; start < change.replacement.length();) {
            int style = change.replacement.styleAt(start), end = start + 1;
            while (end < change.replacement.length() && change.replacement.styleAt(end) == style) end++;
            if (style != TerminalTranscript.NORMAL) replacement.setSpan(
                    new ForegroundColorSpan(style == TerminalTranscript.ERROR ? ERROR_COLOR : PROMPT_COLOR),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end;
        }
        // Remove all old spans in the replaced suffix, including a fragmented prompt's old span.
        for (ForegroundColorSpan span : buffer.getSpans(change.replaceFrom, buffer.length(), ForegroundColorSpan.class)) {
            if (buffer.getSpanEnd(span) > change.replaceFrom) {
                int start = buffer.getSpanStart(span);
                buffer.removeSpan(span);
                // A stderr span can start on an earlier line that this patch does not replace.
                if (start < change.replaceFrom) buffer.setSpan(new ForegroundColorSpan(span.getForegroundColor()),
                        start, change.replaceFrom, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        buffer.replace(change.replaceFrom, buffer.length(), replacement);
        view.post(() -> {
            ViewParent parent = view.getParent();
            if (parent instanceof ScrollView) ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
        });
    }

    /** One instance per shell, with independent incremental stdout and stderr decoding. */
    static final class Output implements InteractiveShell.Listener {
        private final Handler handler;
        private final BooleanSupplier current;
        private final TerminalRenderer renderer;
        private final TerminalOutputDecoder stdout = new TerminalOutputDecoder(), stderr = new TerminalOutputDecoder();
        private final TerminalTranscript.Pending pending = new TerminalTranscript.Pending();
        private final Runnable flushTask = this::flush;
        private boolean posted;

        Output(Handler handler, BooleanSupplier current, TerminalRenderer renderer) {
            this.handler = handler;
            this.current = current;
            this.renderer = renderer;
        }

        private void append(String text, int style) {
            if (text.isEmpty()) return;
            synchronized (pending) {
                pending.append(text, style);
                if (!posted) { posted = true; handler.postDelayed(flushTask, 32); }
            }
        }

        @Override public void output(boolean error, byte[] bytes) {
            append((error ? stderr : stdout).accept(bytes), error ? TerminalTranscript.ERROR : TerminalTranscript.NORMAL);
        }

        @Override public void ended(int code, IOException error) {
            append(stdout.finish(), TerminalTranscript.NORMAL);
            append(stderr.finish(), TerminalTranscript.ERROR);
            String message = error == null ? null : String.valueOf(error.getMessage());
            append(safeText(TerminalTranscript.endedText(code, message)), TerminalTranscript.endedStyle(code, message));
        }

        void error(Exception failure) {
            append(safeText("\n[错误] " + failure.getMessage() + "\n"), TerminalTranscript.ERROR);
        }

        /** UI thread only. Clear calls this first so pre-clear batches cannot reappear afterward. */
        void flush() {
            TerminalTranscript.Text batch;
            synchronized (pending) {
                handler.removeCallbacks(flushTask);
                batch = pending.drain();
                posted = false;
            }
            if (current.getAsBoolean()) renderer.append(batch);
        }

        private static String safeText(String value) {
            TerminalOutputDecoder decoder = new TerminalOutputDecoder();
            return decoder.accept(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + decoder.finish();
        }
    }
}
