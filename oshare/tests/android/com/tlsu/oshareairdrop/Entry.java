package com.tlsu.oshareairdrop;
/** Test-only logger stub: the app_process harness does not load LSPosed. */
public final class Entry {
    public static final String TAG = "OShareAirDrop";
    public static final String HOST = "com.google.android.mosey";
    public static final String BOOT_EXTRA = "oshare_bridge_boot";
    public static Entry instance;
    public void log(int level, String tag, String message) {}
    public void log(int level, String tag, String message, Throwable error) {}
}
