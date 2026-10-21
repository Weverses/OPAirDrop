package com.tlsu.oshareairdrop;

import android.os.SystemClock;
import android.util.Log;

/** Small monotonic milestones, without peer identities or user content. */
final class StartupTrace {
    private StartupTrace() {}
    static void mark(String stage) {
        String message = "STARTUP stage=" + stage + " elapsed_ms=" + SystemClock.elapsedRealtime();
        Log.i(Entry.TAG, message);
        if (Entry.instance != null) Entry.instance.log(Log.INFO, Entry.TAG, message);
    }
}
