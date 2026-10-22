package com.tlsu.oshareairdrop;

import android.os.SystemClock;
import android.util.Log;

/** Small monotonic milestones, without peer identities or user content. */
final class StartupTrace {
    private StartupTrace() {}
    static void mark(String stage) {
        String message = "STARTUP stage=" + stage + " elapsed_ms=" + SystemClock.elapsedRealtime();
        android.content.Context context = HostContext.get();
        if (context != null) try {
            java.io.File path = new java.io.File(context.getFilesDir(), "opairshare-startup.log");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(path, path.length() < 65536)) {
                out.write((message + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException | RuntimeException ignored) {}
        Log.i(Entry.TAG, message);
        if (Entry.instance != null) Entry.instance.log(Log.INFO, Entry.TAG, message);
    }
}
