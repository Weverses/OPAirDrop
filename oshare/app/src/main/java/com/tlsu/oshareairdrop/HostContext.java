package com.tlsu.oshareairdrop;

import android.content.Context;

public final class HostContext {
    private static volatile Context context;

    private HostContext() {}

    public static void set(Context value) {
        context = value;
    }

    public static Context get() {
        return context;
    }
}
