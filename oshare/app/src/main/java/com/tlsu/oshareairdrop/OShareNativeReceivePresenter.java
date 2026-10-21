package com.tlsu.oshareairdrop;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Uses the installed, signed OShare templates and SDK; owns no native transfer singleton. */
final class OShareNativeReceivePresenter {
    private static final String ACTION = "pantanal.intent.business.app.system.OSHARE_USER_CONFIRM";
    private static final String PREFIX = "opairshare-";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    interface Host {
        void changed(String session);
        void open(String session);
        boolean command(String session, String action);
    }
    private final Context context;
    private final Entry module;
    private final OShareSeedlingResolver.Roles roles;
    private final Host host;
    private final Map<String, Card> sessions = new LinkedHashMap<>(); // main thread only
    private final ConcurrentHashMap<String, Card> instances = new ConcurrentHashMap<>();
    private final ArrayList<HookHandle> hooks = new ArrayList<>();
    private final LinkedHashMap<String, Long> retiredEndTimes = new LinkedHashMap<>();
    private static final java.util.concurrent.ExecutorService SDK_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(
            action -> new Thread(action, "OShare-Seedling"));
    private long nextSupportCheck;
    private boolean supportAvailable;

    private static final class Card {
        final String session, instance, scene;
        final int notification;
        JSONObject snapshot, data;
        Object nativeCard, options;
        final LinkedHashMap<String, Object> hosts = new LinkedHashMap<>();
        volatile boolean failed, ended, visible;
        boolean verified;
        String fingerprint = "";
        Card(String session, String scene, int notification) {
            this.session = session; this.scene = scene; this.notification = notification;
            this.instance = PREFIX + UUID.randomUUID();
        }
    }

    static OShareNativeReceivePresenter install(Entry module, Context context, ClassLoader loader, Host host) {
        OShareNativeReceivePresenter presenter = null;
        try {
            OShareSeedlingAssets.prepare(context);
            if (!OShareSeedlingAssets.ready() || !OShareSeedlingAssets.scopedCancel()) throw new IllegalStateException("native receive template unavailable");
            presenter = new OShareNativeReceivePresenter(module, context,
                    OShareSeedlingResolver.resolve(module, context, loader), host);
            presenter.installHooks();
            module.log(Log.INFO, Entry.TAG, "Native OShare Seedling receive presentation installed");
            return presenter;
        } catch (Throwable failure) {
            if (presenter != null) for (HookHandle hook : presenter.hooks)
                try { hook.unhook(); } catch (Throwable ignored) {}
            module.log(Log.WARN, Entry.TAG, "Native receive presentation unavailable; using dialog", failure);
            return null;
        }
    }

    private OShareNativeReceivePresenter(Entry module, Context context,
            OShareSeedlingResolver.Roles roles, Host host) {
        this.module = module; this.context = context; this.roles = roles; this.host = host;
    }

    private void installHooks() {
        hooks.add(module.hook(roles.providerCall).intercept(chain -> {
            Bundle bundle = (Bundle) chain.getArg(2);
            String instance = bundle == null ? null : bundle.getString("instanceId");
            // Retired bridge IDs are consumed too: never reinterpret a delayed click
            // as an action on OShare's unrelated native transfer singleton.
            if (instance == null || !instance.startsWith(PREFIX)) return chain.proceed();
            if (trustedCaller()) {
                String event = bundle.getString("event", "");
                Card card = instances.get(instance);
                MAIN.post(() -> handleAction(card, event));
            }
            Bundle result = new Bundle(); result.putInt("call_result", 0); result.putBoolean("consumed", true);
            return result;
        }));
        hooks.add(module.hook(roles.providerUpdate).intercept(chain -> {
            Object nativeCard = chain.getArg(1);
            String id = roles.cardId(nativeCard);
            if (id == null || !id.startsWith(PREFIX)) return chain.proceed();
            if (trustedCaller()) MAIN.post(() -> cardCreated(id, nativeCard, true));
            return null;
        }));
        hookLifecycle(roles.providerShow, true, false);
        hookLifecycle(roles.providerHide, false, false);
        hookLifecycle(roles.providerDestroy, false, true);
    }

    private void hookLifecycle(Method method, boolean shown, boolean destroyed) {
        if (method == null) return;
        hooks.add(module.hook(method).intercept(chain -> {
            Object nativeCard = chain.getArg(1);
            String id = roles.cardId(nativeCard);
            if (id == null || !id.startsWith(PREFIX)) return chain.proceed();
            if (trustedCaller()) MAIN.post(() -> {
                Card card = instances.get(id);
                if (card == null || card.ended) return;
                if (shown) cardCreated(id, nativeCard, false);
                else if (destroyed) {
                    try { card.hosts.remove(roles.widgetCode(nativeCard)); }
                    catch (Throwable failure) { log("Native card host identity unavailable", failure); }
                    card.nativeCard = card.hosts.isEmpty() ? null : card.hosts.values().iterator().next();
                    card.visible = !card.hosts.isEmpty();
                    host.changed(card.session);
                }
                // A hidden panel can still have a live status-bar/notification card.
                // The original OShare provider also retains its card on onHide.
            });
            return null;
        }));
    }

    private boolean trustedCaller() {
        int uid = Binder.getCallingUid();
        return uid == Process.myUid() || uid == Process.SYSTEM_UID
                || context.checkPermission("com.oplus.permission.safe.ASSISTANT", Binder.getCallingPid(), uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Returns true only after the system materializes a matching-version native card. */
    boolean update(JSONObject event, int notification, String title, String summary) {
        String session = event.optString("session");
        if (session.isEmpty()) return false;
        Card previous = sessions.get(session);
        if (previous == null && event.optBoolean("terminal")) return false; // no replay of old success cards
        try {
            if (!supported()) { if (previous != null) end(previous); return false; }
            String scene = scene(event);
            Card card = previous;
            if (card == null || !card.scene.equals(scene)) {
                if (card != null) end(card);
                card = new Card(session, scene, notification);
                sessions.put(session, card); instances.put(card.instance, card);
                prune();
            }
            if (card.failed || card.ended) return false;
            card.snapshot = new JSONObject(event.toString());
            card.data = data(card, title, summary, false);
            card.options = options(card);
            String next = card.data.toString();
            if (card.fingerprint.isEmpty()) {
                start(card, title, summary);
            } else if (!next.equals(card.fingerprint) && card.nativeCard != null && card.verified) {
                push(card);
            }
            card.fingerprint = next;
            return replacesDialog(session);
        } catch (Throwable failure) {
            Card card = sessions.get(session);
            if (card != null) fail(card, failure);
            else log("Native receive presentation failed", failure);
            return false;
        }
    }

    private boolean supported() throws ReflectiveOperationException {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= nextSupportCheck) {
            supportAvailable = roles.supported(context);
            nextSupportCheck = now + (supportAvailable ? 60_000 : 5_000);
        }
        return supportAvailable;
    }

    boolean replacesDialog(String session) {
        Card card = sessions.get(session);
        if (card == null || !card.visible || !card.verified || card.failed || card.ended) return false;
        // Old signed templates have no per-instance Accept/Decline. Keep the
        // session-aware invitation and completed-file entry alongside their capsule.
        return card.scene.equals("transfer") || card.scene.equals("waiting")
                || (card.scene.equals("invite") && OShareSeedlingAssets.supportsScopedConfirm());
    }

    private static String scene(JSONObject event) {
        String phase = event.optString("phase");
        if (event.optBoolean("terminal")) return "result";
        if (phase.equals("pending")) return "invite";
        if (phase.equals("receiving")) return "transfer";
        return "waiting";
    }

    private void start(Card card, String title, String summary) throws Exception {
        // Start with no actionable controls. Only enable template interactions
        // after the host returns its UPK version and the exact instance identity.
        JSONObject bootstrap = data(card, title, summary, true);
        Object intent = roles.newIntent(System.currentTimeMillis(), ACTION, true, null,
                new JSONObject().put("initData", bootstrap.toString()), card.options, card.instance);
        Object callback = CallbackFactory.create(roles.intentCallbackType.getClassLoader(),
                roles.intentCallbackType.getName(), (method, args) -> {
                    if (args == null || args.length < 3 || !ACTION.equals(String.valueOf(args[0]))) return null;
                    int flag = ((Number) args[1]).intValue();
                    boolean ok = method.equals("onIntentResult") ? Boolean.TRUE.equals(args[2])
                            : method.equals("onIntentResultCodeCallBack") && (((Number) args[2]).intValue() == 0 || ((Number) args[2]).intValue() == 2);
                    module.log(Log.INFO, Entry.TAG, "SEEDLING_ASYNC_RESULT method=" + method + " flag=" + flag
                            + " value=" + args[2] + " instance=" + card.instance);
                    if (flag == 1 && !ok) MAIN.post(() -> fail(card, new IllegalStateException(
                            "Seedling async START rejected: " + method + " result=" + args[2])));
                    return null;
                });
        SDK_WORKER.execute(() -> {
            if (card.ended) return;
            try {
                Object transportResult = roles.sendSeedling.invoke(roles.sdk, context, intent, callback);
                // This is UMS IntentProvider's immediate transport reply, not
                // IIntentResultCallBack's asynchronous card-result code. Native
                // OShare ignores it. In particular, reply 1 must not trigger END.
                module.log(Log.INFO, Entry.TAG, "SEEDLING_START_SUBMITTED transport=" + transportResult
                        + " scene=" + card.scene + " instance=" + card.instance);
                MAIN.postDelayed(() -> {
                    if (!card.verified && !card.ended && !card.failed)
                        fail(card, new IllegalStateException("native card creation timed out"));
                }, 3500);
            } catch (Throwable failure) { MAIN.post(() -> fail(card, failure)); }
        });
        if (card.snapshot.optBoolean("terminal")) MAIN.postDelayed(() -> endAndNotify(card), 12_000);
    }

    private void cardCreated(String id, Object nativeCard, boolean requestingData) {
        Card card = instances.get(id);
        if (card == null || card.ended || card.failed) {
            retireLateCard(id);
            return;
        }
        try {
            if (roles.cardUpkVersion(nativeCard) != OShareSeedlingAssets.versionCode())
                throw new IllegalStateException("system receive template version differs from installed OShare");
            boolean first = !card.verified;
            card.nativeCard = nativeCard; card.verified = true;
            card.hosts.put(roles.widgetCode(nativeCard), nativeCard);
            card.visible = true;
            if (first) module.log(Log.INFO, Entry.TAG, "SEEDLING_CARD_CREATED scene=" + card.scene
                    + " version=" + roles.cardUpkVersion(nativeCard) + " instance=" + card.instance);
            if (requestingData) sendData(card, nativeCard, false);
            else if (first) push(card);
            host.changed(card.session);
        } catch (Throwable failure) { fail(card, failure); }
    }

    private void push(Card card) {
        if (card.nativeCard != null) sendData(card, card.nativeCard, true);
    }

    private void sendData(Card card, Object nativeCard, boolean allHosts) {
        JSONObject data = card.data;
        Object options = card.options;
        SDK_WORKER.execute(() -> {
            if (card.ended || card.failed) return;
            try {
                if (allHosts) roles.updateAllCardData.invoke(roles.sdk, nativeCard, card.instance, data, options, 2, null);
                else roles.updateData.invoke(roles.sdk, nativeCard, data, options);
            } catch (Throwable failure) { MAIN.post(() -> fail(card, failure)); }
        });
    }

    private Object options(Card card) throws Exception {
        Object options = roles.newOptions();
        roles.setPageId.invoke(options, "pages/index");
        roles.setMilestone.invoke(options, true);
        roles.setGrade.invoke(options, Integer.valueOf(4));
        roles.setNotificationIdList.invoke(options, Collections.singletonList(card.notification));
        roles.setRequestShowPanel.invoke(options, Boolean.valueOf(card.scene.equals("invite")
                && OShareSeedlingAssets.supportsScopedConfirm()));
        roles.setRequestHideStatusBar.invoke(options, false);
        roles.setLockScreenShowHostMap.invoke(options, Collections.singletonMap(roles.hostStatusBar, false));
        return options;
    }

    private JSONObject data(Card card, String title, String summary, boolean bootstrap) throws Exception {
        JSONObject data = OShareSeedlingAssets.data();
        JSONObject event = card.snapshot;
        boolean terminal = event.optBoolean("terminal");
        boolean invite = card.scene.equals("invite");
        boolean scopedConfirm = invite && OShareSeedlingAssets.supportsScopedConfirm();
        boolean progress = !bootstrap && !terminal && !scopedConfirm;
        int value = Math.max(0, Math.min(100, event.optInt("progress")));
        String phase = event.optString("phase");
        String capsule = invite ? "待接收" : phase.equals("publishing") ? "保存中"
                : phase.equals("verifying") ? "校验中" : terminal ? (event.optBoolean("published") ? "接收完成" : "接收结束")
                : value + "%";
        data.put("instanceId", card.instance).put("capsule_title", capsule).put("panel_title", title)
                .put("detailsText", summary + (invite && !scopedConfirm ? "\n点按进度图标查看接收邀请" : ""))
                .put("showCancel", !bootstrap && scopedConfirm).put("showConfirm", !bootstrap && scopedConfirm)
                .put("isE4Show", false).put("showD1", true).put("isB0Show", true)
                .put("isShowProcess", progress).put("progress", invite ? 0 : value)
                .put("isShowPreview", false).put("isReceiving", !terminal)
                .put("isShowLottie", !bootstrap && terminal).put("playLottie", true)
                .put("repeat", terminal ? 0 : -1).put("stop", false)
                .put("accept", "接受").put("reject", "拒绝").put("accept_text", "接受").put("reject_text", "拒绝")
                .put("icon_process_d", "{{$r('images." + (card.scene.equals("transfer") ? "cancel" : "download") + "')}}");
        boolean success = terminal && event.optBoolean("published");
        data.put("state_capsule", "{{$r('lotties." + (success ? "success_capsule" : "fail_capsule") + "')}}")
                .put("state", "{{$r('lotties." + (success ? "success" : "fail") + "')}}");
        return data;
    }

    private void handleAction(Card card, String event) {
        if (card == null || card.ended || card.failed || !card.verified || !card.visible
                || sessions.get(card.session) != card) return;
        android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
        if (keyguard == null || keyguard.isKeyguardLocked()) { host.open(card.session); return; }
        if (event.equals("cancel") && OShareSeedlingAssets.scopedCancel()) {
            if (card.scene.equals("transfer")) host.command(card.session, "cancel_incoming");
            else host.open(card.session);
        } else if (card.scene.equals("invite") && OShareSeedlingAssets.supportsScopedConfirm()) {
            if (event.equals("accept")) host.command(card.session, "accept");
            else if (event.equals("decline")) host.command(card.session, "deny");
        }
    }

    void dismiss(String session) { Card card = sessions.get(session); if (card != null) endAndNotify(card); }

    private void fail(Card card, Throwable failure) {
        if (card.failed || card.ended) return;
        card.failed = true; log("Native receive card fallback", failure); endAndNotify(card);
    }

    private void endAndNotify(Card card) { end(card); host.changed(card.session); }

    private void end(Card card) {
        if (card.ended) return;
        card.ended = true; card.visible = false; instances.remove(card.instance);
        sendEnd(card.instance, 0);
    }

    private void retireLateCard(String instance) {
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = retiredEndTimes.get(instance);
        if (last != null && now - last < 5000) return;
        retiredEndTimes.put(instance, now);
        while (retiredEndTimes.size() > 64) retiredEndTimes.remove(retiredEndTimes.keySet().iterator().next());
        sendEnd(instance, 0);
    }

    private void sendEnd(String instance, int attempt) {
        SDK_WORKER.execute(() -> {
            try {
                Object end = roles.newIntent(System.currentTimeMillis(), ACTION, false, null, null, null, instance);
                Object transportResult = roles.sendSeedling.invoke(roles.sdk, context, end, null);
                // END has the same transport contract; retry only real invocation
                // failures, never an undocumented nonzero transport reply.
                module.log(Log.INFO, Entry.TAG, "SEEDLING_END_SUBMITTED transport=" + transportResult
                        + " instance=" + instance);
            } catch (Throwable failure) {
                log("Native receive card END failed", failure);
                if (attempt < 2) MAIN.postDelayed(() -> sendEnd(instance, attempt + 1), 1500L * (attempt + 1));
            }
        });
    }

    private void prune() {
        if (sessions.size() <= 64) return;
        java.util.Iterator<Map.Entry<String, Card>> it = sessions.entrySet().iterator();
        while (sessions.size() > 64 && it.hasNext()) {
            Card card = it.next().getValue();
            if (card.snapshot != null && card.snapshot.optBoolean("terminal")) { end(card); it.remove(); }
        }
    }

    private void log(String message, Throwable failure) { module.log(Log.WARN, Entry.TAG, message, failure); }
}
