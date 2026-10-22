package com.tlsu.oshareairdrop;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.libxposed.api.XposedInterface.HookHandle;

final class Entry {
    static final String TAG = "test";
    final Map<String, Interceptor> hooks = new LinkedHashMap<>();
    int failAfter = Integer.MAX_VALUE;

    interface Chain { Object proceed() throws Exception; }
    interface Interceptor { Object intercept(Chain chain) throws Exception; }

    Builder hook(Method method) {
        return new Builder(method.getName());
    }

    final class Builder {
        final String name;
        Builder(String name) { this.name = name; }
        HookHandle intercept(Interceptor interceptor) {
            if (hooks.size() == failAfter) throw new IllegalStateException("hook failure");
            hooks.put(name, interceptor);
            return () -> hooks.remove(name);
        }
    }
}
