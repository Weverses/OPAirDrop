package com.tlsu.oshareairdrop;

public final class ProviderHolder {
    private static volatile Object provider;
    private static volatile ClassLoader hostClassLoader;

    private ProviderHolder() {}

    public static void install(ClassLoader loader) {
        hostClassLoader = loader;
    }

    public static ClassLoader loader() {
        return hostClassLoader;
    }

    public static void set(Object value) {
        provider = value;
    }

    public static Object get() {
        return provider;
    }

    public static boolean ready() {
        return provider != null && hostClassLoader != null;
    }
}
