package io.github.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import io.github.devicebridge.core.AdbSession;
import io.github.devicebridge.core.HostServerClient;
import io.github.devicebridge.core.OperationScope;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Runs the unmodified production controller with memory UI plumbing and real loopback core. */
public final class ExternalAdbControllerTests {
    private static final String ONLINE = "SYNTHETIC_A\tdevice model:Memory_Pixel transport_id:17\n";
    private static final String MIXED = ONLINE
        + "SYNTHETIC_A\tdevice model:Second_Transport transport_id:18\n"
        + "SYNTHETIC_OFF\toffline transport_id:19\n"
        + "SYNTHETIC_AUTH\tunauthorized transport_id:20\n"
        + "SYNTHETIC_NO_ID\tdevice\n"
        + "SYNTHETIC_PERM\tno permissions transport_id:21\n";
    private static int checks, scenarios;
    private interface Test { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        run("refresh routing and cancellation", ExternalAdbControllerTests::refreshRouting);
        run("address validation and explicit trust confirmation", ExternalAdbControllerTests::addressAndTrust);
        run("successful connect, labels and exact transport selection", ExternalAdbControllerTests::devicesAndSelection);
        run("empty list and failed-server guidance", ExternalAdbControllerTests::emptyAndFailed);
        run("inactive home and Activity guards", ExternalAdbControllerTests::inactiveEntrypoints);
        run("stale address and confirmation callbacks", ExternalAdbControllerTests::staleConfiguration);
        run("failed replacement preserves selected server and releases sockets", ExternalAdbControllerTests::failedReplacement);
        run("late connection closes on stale home or failed CAS", ExternalAdbControllerTests::lateConnection);
        run("successful replacement closes old binding", ExternalAdbControllerTests::successfulReplacement);
        run("refresh callbacks and expired device dialogs", ExternalAdbControllerTests::staleRefreshAndDialogs);
        run("late selected sessions are closed", ExternalAdbControllerTests::lateSelection);
        run("pre-cancelled jobs perform zero requests", ExternalAdbControllerTests::preCancelledJobs);
        run("local reset accurately describes its scope", ExternalAdbControllerTests::localReset);
        run("manage reconnect and app-only disconnect", ExternalAdbControllerTests::manageAndDisconnect);
        run("stop warning, cancel and stale confirmation", ExternalAdbControllerTests::stopConfirmation);
        run("stop accepted, refused and unknown stay distinct", ExternalAdbControllerTests::stopOutcomes);
        run("late stop result never redraws an expired home", ExternalAdbControllerTests::staleStopResult);
        System.out.println("HOST UI CONTROLLER TESTS: " + checks + " assertions passed in " + scenarios + " scenarios");
        System.out.println("Scope: actual ExternalAdbController + core; memory Android/UI/Host/runtime; synthetic 127.0.0.1 only.");
        System.out.println("Not Android pixel/lifecycle integration, real ADB daemon/device, original-APK parity, or CON-05/06 full acceptance.");
    }
    private static void run(String name, Test test) throws Exception {
        int before = checks; test.run(); scenarios++;
        System.out.println("PASS " + name + " (" + (checks - before) + " assertions)");
    }
    private static void refreshRouting() throws Exception {
        try (Fixture f = new Fixture()) {
            f.controller.refresh(); AlertDialog menu = dialog();
            eq(Arrays.asList("检查USB设备", "连接外部ADB服务"), Arrays.asList(menu.items), "refresh explicitly offers USB or external server");
            check(f.activity.owned.contains(menu), "controller owns refresh dialog");
            menu.negative(); eq(0, f.host.jobs.size(), "cancel refresh menu queues no work");
            f.controller.refresh(); dialog().choose(0); eq(1, f.host.usb, "USB route calls Host once");
            f.controller.refresh(); dialog().choose(1); eq(1, Ui.prompts.size(), "external route asks address");
            f.controller.refresh(); AlertDialog stale = dialog(); f.host.current = false; stale.choose(0);
            eq(1, f.host.usb, "expired home cannot call USB");
            eq(0, f.host.jobs.size(), "routing never implicitly starts network work");
        }
    }
    private static void addressAndTrust() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE);
            f.controller.configure(); eq("127.0.0.1:5037", prompt().value, "default smart-socket port is explicit");
            prompt().cancel(); eq(0, Ui.confirmations.size(), "cancel address has no confirmation");
            f.controller.configure(); prompt().accept("https://127.0.0.1:5037");
            eq("服务地址无效", message().title, "invalid URL presented as validation error");
            eq(0, f.host.jobs.size(), "invalid address never schedules connect");
            f.controller.configure(); prompt().accept(server.endpoint()); Ui.Confirmation confirm = confirmation();
            contains(confirm.message, "服务：" + server.endpoint(), "confirmation names exact dynamic endpoint");
            contains(confirm.message, "不是设备无线调试端口", "server port distinguished from device port");
            contains(confirm.message, "可信", "explicit trust warning");
            contains(confirm.message, "没有TLS身份保证", "smart-socket TLS limitation disclosed");
            contains(confirm.message, "由该服务管理", "server controls identity, authorization and device-link encryption");
            contains(confirm.message, "不会自动扫描、配对或启动服务器", "no implicit discovery/pair/start claim");
            eq(0, server.requests.size(), "merely displaying confirmation performs no requests");
            confirm.cancel(); eq(0, f.host.jobs.size(), "cancel confirmation never starts connect");
            eq(0, server.requests.size(), "cancel sends no synthetic traffic");
        }
    }
    private static void devicesAndSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(MIXED); HostServerClient client = f.connect(server);
            eq(server.endpoint(), f.savedEndpoint(), "successful connect saves exact endpoint");
            AlertDialog list = dialog(); contains(list.title, server.endpoint(), "list names selected server");
            eq(6, list.items.length, "online and unavailable devices stay visible");
            contains(list.items[0], "Memory_Pixel (SYNTHETIC_A) · device", "online model, serial and state shown");
            contains(list.items[0], "transport_id: 17", "first exact transport ID shown");
            contains(list.items[1], "transport_id: 18", "duplicate serial second ID distinguishable");
            contains(list.items[2], "offline", "offline state shown");
            contains(list.items[3], "unauthorized", "unauthorized state shown");
            contains(list.items[4], "未提供，不能可靠选择", "missing ID unreliability disclosed");
            contains(list.items[5], "no permissions", "permission state shown");
            check(f.activity.owned.contains(list), "controller owns list dialog");
            for (int index = 2; index < 6; index++) {
                AlertDialog current = f.list(); int jobs = f.host.jobs.size(); current.choose(index);
                eq(jobs, f.host.jobs.size(), "unavailable row schedules no session " + index);
                eq("设备不可选择", message().title, "unavailable row explains selection failure " + index);
                contains(message().message, "稳定transport_id", "unavailable row requires stable ID " + index);
            }
            f.list().choose(1); Pending<?> selection = f.host.last(); selection.execute(); selection.deliver();
            eq(1, f.host.opened.size(), "online selection opens exactly once");
            AdbSession selected = f.host.opened.get(0);
            eq("ADB server " + server.endpoint() + " / SYNTHETIC_A #18", selected.target(), "session binds the chosen transport among duplicate serials");
            eq(1, server.count("host-transport-id:18:features"), "real session handshake selects transport 18");
            eq(0, server.count("host-transport-id:17:features"), "selection never silently switches to first serial match");
            check(!selected.isEncrypted(), "relay session makes no TLS claim");
            eq(HostServerClient.State.CONNECTED, client.state(), "selecting keeps external server connected");
            eq(0, server.count("host:kill"), "connect/list/select never asks server to stop");
        }
    }
    private static void emptyAndFailed() throws Exception {
        try (Fixture f = new Fixture()) {
            HostServerClient client = f.connect(f.server(""));
            eq(0, dialog().items.length, "empty list has no fabricated devices");
            contains(dialog().message, "服务未列出设备", "empty list explained");
            contains(dialog().message, "宿主", "empty list guides host-side action");
            client.close(); f.controller.refresh();
            contains(dialog().title, "旧服务已失效", "failed/closed server requires explicit selection");
            eq(1, f.host.jobs.size(), "invalid old client not silently reconnected");
        }
    }
    private static void inactiveEntrypoints() throws Exception {
        for (int mode = 0; mode < 3; mode++) try (Fixture f = new Fixture()) {
            if (mode == 0) f.host.current = false;
            if (mode == 1) f.activity.finishing = true;
            if (mode == 2) f.activity.destroyed = true;
            f.controller.refresh(); f.controller.configure(); f.controller.manage();
            eq(0, AlertDialog.SHOWN.size() + Ui.prompts.size() + Ui.confirmations.size() + f.host.jobs.size(), "inactive entrypoints stay quiet mode " + mode);
        }
    }
    private static void staleConfiguration() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE);
            f.controller.configure(); f.host.current = false; prompt().accept(server.endpoint());
            eq(0, Ui.confirmations.size(), "expired prompt has no next confirmation");
        }
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.controller.configure();
            f.install(server); prompt().accept(server.endpoint());
            eq(0, Ui.confirmations.size(), "changed selected server invalidates old address prompt");
        }
        for (boolean changedServer : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.prepareConnect(server);
            if (changedServer) f.install(server); else f.host.current = false;
            confirmation().accept(); eq(0, f.host.jobs.size(), "stale connect confirmation does not queue work " + changedServer);
            eq(changedServer ? 1 : 0, server.count("host:track-devices-l"), "confirmation produces no extra connection " + changedServer);
        }
    }
    private static void failedReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer oldServer = f.server(ONLINE); HostServerClient old = f.connect(oldServer);
            SyntheticHostServer rejected = f.server(ONLINE); rejected.rejectTracker = true;
            f.prepareConnect(rejected); contains(confirmation().message, "成功更换后会关闭", "replacement explains old session close only on success");
            contains(confirmation().message, "不会停止旧服务器或其他客户端", "replacement does not promise global stop");
            confirmation().accept(); Pending<?> job = f.host.last(); job.execute();
            check(job.error instanceof HostServerClient.ServerFailure, "rejected candidate reports real core failure");
            job.deliver(); contains(message().message, "synthetic connection refused", "failure reason reaches modeled host error surface");
            eq(old, BridgeRuntime.get().externalServer(), "failed replacement retains old selected server");
            eq(HostServerClient.State.CONNECTED, old.state(), "failed replacement does not close old server");
            eq(oldServer.endpoint(), f.savedEndpoint(), "failed replacement does not overwrite saved endpoint");
            check(rejected.allSocketsClosed(), "failed candidate has no remaining sockets");
            eq(0, rejected.count("host:kill") + oldServer.count("host:kill"), "replacement failure never kills either server");
        }
    }
    private static void lateConnection() throws Exception {
        for (boolean failedCas : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            SyntheticHostServer candidateServer = f.server(ONLINE); f.prepareConnect(candidateServer); confirmation().accept();
            Pending<?> job = f.host.last(); job.execute(); HostServerClient candidate = (HostServerClient) job.value;
            HostServerClient newer = null;
            if (failedCas) newer = f.install(f.server(ONLINE)); else f.host.current = false;
            int windows = AlertDialog.SHOWN.size(); job.deliver();
            eq(HostServerClient.State.CLOSED, candidate.state(), "unadopted late candidate closed " + failedCas);
            check(candidateServer.allSocketsClosed(), "unadopted candidate tracker released " + failedCas);
            eq(newer, BridgeRuntime.get().externalServer(), "late candidate never replaces newer selection " + failedCas);
            eq(null, f.savedEndpoint(), "late candidate never saves endpoint " + failedCas);
            eq(windows, AlertDialog.SHOWN.size(), "late candidate opens no list " + failedCas);
        }
    }
    private static void successfulReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer oldServer = f.server(ONLINE); HostServerClient old = f.connect(oldServer);
            dialog().choose(0); f.host.last().execute(); f.host.last().deliver(); AdbSession oldSession = f.host.opened.get(0);
            SyntheticHostServer nextServer = f.server(ONLINE); HostServerClient next = f.connect(nextServer);
            eq(next, BridgeRuntime.get().externalServer(), "successful candidate adopted");
            eq(HostServerClient.State.CLOSED, old.state(), "runtime stand-in closes replaced core client");
            eq(AdbSession.State.FAILED, oldSession.state(), "old actual core device binding invalidated");
            check(oldServer.allSocketsClosed(), "old tracker resources released");
            eq(nextServer.endpoint(), f.savedEndpoint(), "successful replacement updates saved endpoint");
            eq(0, oldServer.count("host:kill"), "closing old client is not a daemon stop");
        }
    }
    private static void staleRefreshAndDialogs() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.connect(server);
            dialog().positive(); Pending<?> refresh = f.host.last(); refresh.execute(); int windows = AlertDialog.SHOWN.size();
            f.host.current = false; refresh.deliver(); eq(windows, AlertDialog.SHOWN.size(), "late refresh cannot show on expired home");
            eq(1, server.count("host:devices-l"), "refresh button runs actual devices-l once");
        }
        try (Fixture f = new Fixture()) {
            HostServerClient old = f.connect(f.server(ONLINE)); AlertDialog stale = dialog();
            f.controller.refresh(); Pending<?> refresh = f.host.last(); refresh.execute();
            f.install(f.server(ONLINE)); int windows = AlertDialog.SHOWN.size(); refresh.deliver();
            eq(windows, AlertDialog.SHOWN.size(), "late refresh for old selected server suppressed");
            int jobs = f.host.jobs.size(); stale.choose(0); stale.positive(); stale.neutral();
            eq(jobs, f.host.jobs.size(), "old-server rows and actions cannot select or refresh");
            eq(0, f.host.opened.size(), "old-server row opens nothing");
            old.close();
        }
        try (Fixture f = new Fixture()) {
            HostServerClient client = f.connect(f.server(ONLINE)); f.controller.refresh(); Pending<?> refresh = f.host.last(); refresh.execute();
            client.close(); refresh.deliver(); eq("服务连接已失效", message().title, "closed client cannot display valid selectable snapshot");
            contains(message().message, "不会按旧序列号自动重绑", "expired snapshot explains no automatic serial rebind");
        }
        try (Fixture f = new Fixture()) {
            f.connect(f.server(ONLINE)); AlertDialog stale = dialog(); int jobs = f.host.jobs.size(); f.host.current = false;
            stale.choose(0); stale.positive(); stale.neutral(); stale.negative();
            eq(jobs, f.host.jobs.size(), "all expired-home list actions do no new work");
            eq(0, f.host.refreshes, "expired-home close cannot refresh home");
            eq(1, Ui.prompts.size(), "expired-home change-server cannot open address prompt");
        }
    }
    private static void lateSelection() throws Exception {
        for (boolean changedServer : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            HostServerClient selectedServer = f.connect(f.server(ONLINE)); dialog().choose(0); Pending<?> job = f.host.last(); job.execute();
            AdbSession session = (AdbSession) job.value;
            if (changedServer) f.install(f.server(ONLINE)); else f.host.current = false;
            job.deliver(); eq(AdbSession.State.CLOSED, session.state(), "late session is closed " + changedServer);
            eq(0, f.host.opened.size(), "late session does not reach Host.open " + changedServer);
            eq(HostServerClient.State.CONNECTED, selectedServer.state(), "closing only late session preserves source client " + changedServer);
        }
    }
    private static void preCancelledJobs() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.prepareConnect(server); confirmation().accept();
            Pending<?> job = f.host.last(); job.scope.close(); job.execute();
            check(job.error != null, "pre-cancelled connection fails"); eq(0, server.requests.size(), "pre-cancelled connection sends nothing");
        }
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.connect(server);
            f.controller.refresh(); Pending<?> refresh = f.host.last(); refresh.scope.close(); refresh.execute();
            eq(0, server.count("host:devices-l"), "pre-cancelled refresh sends nothing");
            dialog().choose(0); Pending<?> select = f.host.last(); select.scope.close(); select.execute();
            eq(0, server.count("host-transport-id:17:features"), "pre-cancelled selection sends nothing");
            f.prepareStop(); confirmation().accept(); Pending<?> stop = f.host.last(); stop.scope.close(); stop.execute();
            eq(0, server.count("host:kill"), "pre-cancelled stop sends nothing");
            eq(0, f.host.opened.size(), "cancelled work never opens a session");
        }
    }
    private static void localReset() throws Exception {
        try (Fixture f = new Fixture()) {
            f.controller.manage(); contains(confirmation().message, "断开", "no-server action accurately describes session disconnect");
            contains(confirmation().message, "没有需重启的本机native ADB服务器", "local reset never pretends a native daemon exists");
            confirmation().cancel(); eq(0, BridgeRuntime.get().disconnects, "cancel local reset does nothing");
            f.controller.manage(); confirmation().accept(); eq(1, BridgeRuntime.get().disconnects, "confirmed reset calls local disconnect once");
            eq(1, f.host.refreshes, "confirmed reset refreshes current home");
            eq(0, f.host.jobs.size(), "local reset does not schedule server network work");
        }
        for (boolean changedServer : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            f.controller.manage(); if (changedServer) f.install(f.server(ONLINE)); else f.host.current = false;
            confirmation().accept(); eq(0, BridgeRuntime.get().disconnects, "stale local reset guarded " + changedServer);
            eq(0, f.host.refreshes, "stale reset cannot refresh home " + changedServer);
        }
    }
    private static void manageAndDisconnect() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); HostServerClient client = f.connect(server);
            f.controller.manage(); AlertDialog menu = dialog(); contains(menu.title, server.endpoint(), "management title binds exact endpoint");
            contains(menu.items[1], "不重启", "reconnect action never claims restart");
            check(f.activity.owned.contains(menu), "management dialog owned"); menu.choose(1);
            eq(server.endpoint(), prompt().value, "reconnect prompts current endpoint"); prompt().cancel();
            eq(1, server.count("host:track-devices-l"), "cancel reconnect performs no additional handshake");
            f.controller.manage(); dialog().choose(2); contains(confirmation().message, server.endpoint(), "app-only disconnect names endpoint");
            contains(confirmation().message, "不停止服务器，不影响其他客户端", "disconnect scope is explicit");
            confirmation().cancel(); eq(client, BridgeRuntime.get().externalServer(), "cancel disconnect leaves selection");
            f.controller.manage(); dialog().choose(2); confirmation().accept();
            eq(null, BridgeRuntime.get().externalServer(), "confirmed disconnect clears selected server");
            eq(HostServerClient.State.CLOSED, client.state(), "confirmed disconnect closes own core client");
            eq(1, f.host.refreshes, "disconnect refreshes home once");
            eq(0, server.count("host:kill"), "app-only disconnect sends no kill request");
        }
        try (Fixture f = new Fixture()) {
            HostServerClient client = f.connect(f.server(ONLINE)); f.controller.manage(); AlertDialog old = dialog(); f.host.current = false;
            int jobs = f.host.jobs.size(), confirms = Ui.confirmations.size(); old.choose(0); old.choose(1); old.choose(2); old.choose(3);
            eq(jobs, f.host.jobs.size(), "stale management menu starts no job");
            eq(confirms, Ui.confirmations.size(), "stale menu opens no consequential confirmation");
            eq(client, BridgeRuntime.get().externalServer(), "stale menu preserves selected server");
        }
    }
    private static void stopConfirmation() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.connect(server); f.prepareStop();
            contains(confirmation().message, "服务：" + server.endpoint(), "stop identifies exact endpoint");
            contains(confirmation().message, "所有客户端及设备会话", "stop discloses shared-server impact");
            contains(confirmation().message, "正在进行的操作会中断", "stop discloses interruption");
            contains(confirmation().message, "没有远程start-server", "stop explains no remote start operation");
            contains(confirmation().message, "服务器宿主重新启动", "stop gives necessary host-side recovery");
            contains(confirmation().message, "不是一键重启", "stop not mislabeled as restart");
            confirmation().cancel(); eq(0, server.count("host:kill"), "cancel stop sends no stop request");
            eq(1, f.host.jobs.size(), "cancel stop queues no additional operation");
        }
        for (boolean changedServer : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.connect(server); f.prepareStop();
            if (changedServer) f.install(f.server(ONLINE)); else f.host.current = false;
            confirmation().accept(); eq(0, server.count("host:kill"), "stale stop confirmation sends nothing " + changedServer);
            eq(1, f.host.jobs.size(), "stale stop confirmation queues nothing " + changedServer);
        }
    }
    private static void stopOutcomes() throws Exception {
        for (SyntheticHostServer.StopReply reply : SyntheticHostServer.StopReply.values()) try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); server.stopReply = reply; HostServerClient client = f.connect(server);
            f.prepareStop(); confirmation().accept(); Pending<?> stop = f.host.last(); stop.execute(); stop.deliver();
            eq(1, server.count("host:kill"), "stop is sent exactly once for " + reply);
            contains(stop.scope.progressSummary(), server.endpoint(), "stop progress records exact endpoint " + reply);
            contains(stop.scope.progressSummary(), "取消或断连不保证服务器未停止", "stop progress preserves uncertain-cancellation warning " + reply);
            check(!message().message.contains("重启成功") && !message().message.contains("已重启"), "no restart-success claim for " + reply);
            if (reply == SyntheticHostServer.StopReply.REFUSE) {
                check(stop.error instanceof HostServerClient.ServerFailure, "explicit refusal remains a failure");
                eq("操作未完成", message().title, "modeled Host error surface receives refusal");
                contains(message().message, "synthetic stop refused", "refusal text preserved");
                eq(HostServerClient.State.CONNECTED, client.state(), "refused stop keeps client usable");
                eq(0, f.host.refreshes, "rejected operation never invokes success completion");
            } else {
                eq(null, stop.error, "controller returns differentiated stop result " + reply);
                eq("ADB服务停止结果", message().title, "controller displays stop-result title " + reply);
                contains(message().message, server.endpoint(), "result identifies stopped/requested endpoint " + reply);
                eq(HostServerClient.State.FAILED, client.state(), "accepted/uncertain result invalidates old epoch " + reply);
                eq(1, f.host.refreshes, "result refreshes home once " + reply);
                if (reply == SyntheticHostServer.StopReply.ACCEPT) {
                    contains(message().message, "已接受停止请求", "ACK only claims accepted stop");
                    contains(message().message, "尚未启动或重新连接", "ACK explicitly says not started or reconnected");
                } else {
                    contains(message().message, "停止结果未知", "lost reply remains unknown");
                    contains(message().message, "可能已经停止", "unknown acknowledges possible side effect");
                    contains(message().message, "勿自动重试", "unknown warns against automatic retry");
                }
            }
            eq(1, server.count("host:track-devices-l"), "stop never reconnects implicitly " + reply);
            check(server.unexpected.isEmpty(), "no start/scan/other service requested " + reply);
        }
    }
    private static void staleStopResult() throws Exception {
        try (Fixture f = new Fixture()) {
            SyntheticHostServer server = f.server(ONLINE); f.connect(server); f.prepareStop(); confirmation().accept();
            Pending<?> stop = f.host.last(); stop.execute(); int messages = Ui.messages.size(); f.host.current = false; stop.deliver();
            eq(1, server.count("host:kill"), "already-completed synthetic stop remains exactly once");
            eq(0, f.host.refreshes, "late stop result cannot refresh expired home");
            eq(messages, Ui.messages.size(), "late stop result cannot display on expired home");
        }
    }
    private static final class TestActivity extends Activity implements Ui.DialogOwner {
        final List<Dialog> owned = new ArrayList<>();
        public void ownDialog(Dialog dialog) { owned.add(dialog); }
    }
    private static final class FakeHost implements ExternalAdbController.Host {
        final TestActivity activity; final List<Pending<?>> jobs = new ArrayList<>(); final List<AdbSession> opened = new ArrayList<>();
        boolean current = true; int refreshes, usb;
        FakeHost(TestActivity activity) { this.activity = activity; }
        public boolean current() { return current; }
        public <T> void run(String title, ExternalAdbController.Job<T> job, Consumer<T> done) { jobs.add(new Pending<T>(this, title, job, done)); }
        // Deliberately no duplicate eligibility guard: controller's own stale callbacks must be safe.
        public void open(AdbSession session) { opened.add(session); }
        public void refreshHome() { refreshes++; }
        public void chooseUsb() { usb++; }
        Pending<?> last() { return jobs.get(jobs.size() - 1); }
    }
    private static final class Pending<T> {
        final FakeHost host; final String title; final ExternalAdbController.Job<T> job; final Consumer<T> done;
        final OperationScope scope = new OperationScope(); T value; Exception error; boolean executed, delivered;
        Pending(FakeHost host, String title, ExternalAdbController.Job<T> job, Consumer<T> done) { this.host = host; this.title = title; this.job = job; this.done = done; }
        void execute() {
            if (executed) throw new AssertionError("Job executed twice"); executed = true;
            try { value = job.run(scope); } catch (Exception failure) { error = failure; }
        }
        void deliver() {
            if (!executed || delivered) throw new AssertionError("Invalid completion order"); delivered = true;
            try {
                if (error == null) done.accept(value);
                // Models the Host error contract, not execution of MainActivity.workScoped itself.
                else if (!scope.isCancelled() && host.current && !host.activity.finishing && !host.activity.destroyed)
                    Ui.message(host.activity, "操作未完成", String.valueOf(error.getMessage()));
            } finally { scope.close(); }
        }
        void cleanup() throws Exception { scope.close(); if (value instanceof Closeable) ((Closeable) value).close(); }
    }
    private static final class Fixture implements AutoCloseable {
        final TestActivity activity = new TestActivity(); final FakeHost host = new FakeHost(activity);
        final ExternalAdbController controller; final List<SyntheticHostServer> servers = new ArrayList<>(); final List<HostServerClient> installed = new ArrayList<>();
        Fixture() { BridgeRuntime.get().reset(); Ui.reset(); AlertDialog.SHOWN.clear(); controller = new ExternalAdbController(activity, host); }
        SyntheticHostServer server(String list) throws Exception { SyntheticHostServer server = new SyntheticHostServer(list); servers.add(server); return server; }
        HostServerClient install(SyntheticHostServer server) throws Exception {
            HostServerClient client = server.client(); installed.add(client); client.connect(2000); BridgeRuntime.get().setForTest(client); return client;
        }
        void prepareConnect(SyntheticHostServer server) { controller.configure(); prompt().accept(server.endpoint()); }
        HostServerClient connect(SyntheticHostServer server) {
            prepareConnect(server); confirmation().accept(); Pending<?> job = host.last(); job.execute();
            if (job.error != null) throw new AssertionError("Synthetic connect failed", job.error); job.deliver(); return (HostServerClient) job.value;
        }
        AlertDialog list() { controller.refresh(); Pending<?> job = host.last(); job.execute(); if (job.error != null) throw new AssertionError(job.error); job.deliver(); return dialog(); }
        void prepareStop() { controller.manage(); dialog().choose(3); }
        String savedEndpoint() { return activity.getSharedPreferences("settings", 0).getString("external_adb_server", null); }
        public void close() throws Exception {
            for (Pending<?> job : host.jobs) job.cleanup(); for (HostServerClient client : installed) client.close(); BridgeRuntime.get().reset();
            for (SyntheticHostServer server : servers) { server.close(); if (!server.unexpected.isEmpty()) throw new AssertionError("Unexpected synthetic requests: " + server.unexpected); }
        }
    }
    private static AlertDialog dialog() { return AlertDialog.SHOWN.get(AlertDialog.SHOWN.size() - 1); }
    private static Ui.Prompt prompt() { return Ui.prompts.get(Ui.prompts.size() - 1); }
    private static Ui.Confirmation confirmation() { return Ui.confirmations.get(Ui.confirmations.size() - 1); }
    private static Ui.Message message() { return Ui.messages.get(Ui.messages.size() - 1); }
    private static void contains(String text, String needle, String label) { check(text.contains(needle), label + " (missing " + needle + " in " + text + ")"); }
    private static void eq(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + " expected=" + expected + " actual=" + actual); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
