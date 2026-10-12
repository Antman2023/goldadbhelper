package io.github.devicebridge.android;
import android.app.Activity;
import android.app.Dialog;
import java.util.ArrayList;
import java.util.List;
/** Memory-only presentation seam. The production controller supplies all text/actions. */
final class Ui {
    interface DialogOwner { void ownDialog(Dialog dialog); }
    interface Input { void accept(String text); }
    static final List<Prompt> prompts = new ArrayList<>();
    static final List<Confirmation> confirmations = new ArrayList<>();
    static final List<Message> messages = new ArrayList<>();
    static final class Prompt {
        final String title, value; private final Input action; private boolean resolved;
        Prompt(String title, String value, Input action) { this.title = title; this.value = value; this.action = action; }
        void accept(String value) { if (!resolved) { resolved = true; action.accept(value); } }
        void cancel() { resolved = true; }
    }
    static final class Confirmation {
        final String title, message; private final Runnable action; private boolean resolved;
        Confirmation(String title, String message, Runnable action) { this.title = title; this.message = message; this.action = action; }
        void accept() { if (!resolved) { resolved = true; action.run(); } }
        void cancel() { resolved = true; }
    }
    static final class Message {
        final String title, message;
        Message(String title, String message) { this.title = title; this.message = message; }
    }
    static void prompt(Activity activity, String title, String value, Input action) { prompts.add(new Prompt(title, value, action)); }
    static void confirm(Activity activity, String title, String message, Runnable action) { confirmations.add(new Confirmation(title, message, action)); }
    static void message(Activity activity, String title, String message) { messages.add(new Message(title, message)); }
    static void reset() { prompts.clear(); confirmations.clear(); messages.clear(); }
}
