package com.tlsu.oshareairdrop;

import android.content.Context;
import android.os.SystemProperties;

public final class ShareModeHooksTest {
    static class Options {}
    static class Callback {}
    static class Target {}

    // Model the actual mixed int/void provider contract that broke initialization.
    static class Controller {
        public int g(Callback callback, Options options) { return 0; }
        public int k(Options options) { return 0; }
        public int i() { return 0; }
        public int f(Options options, Callback callback) { return 0; }
        public int j(Options options) { return 0; }
        public int h() { return 0; }
        public int d(String name, Target target, Callback callback) { return 0; }
        public int a(Target target) { return 0; }
        public int b(Target target) { return 0; }
        public int c(Target target) { return 0; }
        public void m(String path) {}
    }

    static class IncompatibleController {
        public int g(Callback callback, Options options) { return 0; }
        public int k(Options options) { return 0; }
        public int i() { return 0; }
        public int f(Options options, Callback callback) { return 0; }
        public int j(Options options) { return 0; }
        public int h() { return 0; }
        public int d(String name, Target target, Callback callback) { return 0; }
        public int a(Target target) { return 0; }
        public int b(Target target) { return 0; }
        public int c(Target target) { return 0; }
        public int m(String path) { return 0; }
    }

    static ClassLoader loader(Class<?> controller) {
        return new ClassLoader(ShareModeHooksTest.class.getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                return switch (name) {
                    case "cad" -> controller;
                    case "apk", "apj" -> Options.class;
                    case "aps", "aqa" -> Callback.class;
                    case "com.google.android.gms.nearby.sharing.ShareTarget" -> Target.class;
                    default -> super.loadClass(name);
                };
            }
        };
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        SystemProperties.set(ShareMode.PROPERTY, "both");
        ShareModeRuntime.initialize(new Context());
        Entry entry = new Entry();
        ShareModeHooks.install(entry, loader(Controller.class), ShareMode.BOTH);
        check(entry.hooks.size() == 11, "mixed provider contract failed to install");
        int[] calls = {0};
        Entry.Chain directory = () -> { calls[0]++; return null; };

        for (String mode : new String[]{"both", "quickshare"}) {
            SystemProperties.set(ShareMode.PROPERTY, mode);
            check(entry.hooks.get("m").intercept(directory) == null, "void pass-through returned a status");
            check(entry.hooks.get("g").intercept(() -> 7).equals(7), "native status lost");
        }
        check(calls[0] == 2, "native directory setter was blocked");
        SystemProperties.set(ShareMode.PROPERTY, "oshare");
        check(entry.hooks.get("m").intercept(directory) == null, "void no-op returned an Integer");
        check(calls[0] == 2, "native setter changed bridge directory");
        check(entry.hooks.get("g").intercept(() -> 7).equals(1), "disabled discovery reported success");
        check(entry.hooks.get("h").intercept(() -> 7).equals(0), "native stop was not suppressed");
        ShareMode.bridgeCall(() -> entry.hooks.get("m").intercept(directory));
        check(calls[0] == 3, "bridge directory setter was blocked");

        SystemProperties.set(ShareMode.PROPERTY, "both");
        SystemProperties.set("debug.opairshare.share_switching", "1");
        check(entry.hooks.get("f").intercept(() -> 7).equals(1), "new work admitted during switching");
        check(entry.hooks.get("m").intercept(directory) == null && calls[0] == 4,
                "directory update could not drain during switching");
        SystemProperties.set("debug.opairshare.share_switching", "0");

        Entry incompatible = new Entry();
        try {
            ShareModeHooks.install(incompatible, loader(IncompatibleController.class), ShareMode.BOTH);
            throw new AssertionError("incompatible setter accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("Unsupported Mosey method"), "wrong contract failure");
        }
        check(incompatible.hooks.isEmpty(), "hooks changed before full contract validation");

        Entry failing = new Entry();
        failing.failAfter = 4;
        try {
            ShareModeHooks.install(failing, loader(Controller.class), ShareMode.BOTH);
            throw new AssertionError("hook failure was swallowed");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("hook failure"), "wrong hook failure");
        }
        check(failing.hooks.isEmpty(), "partial hooks survived installation failure");
        System.out.println("ShareModeHooks passed: mixed contract, void results, mode isolation and rollback");
    }
}
