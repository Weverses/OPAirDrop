package com.tlsu.oshareairdrop;

import java.lang.reflect.Proxy;

/** Callbacks on apl are in-process Java interfaces, not AIDL binders. */
public final class CallbackFactory {
    public interface Handler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private CallbackFactory() {}

    public static Object create(ClassLoader loader, String interfaceName, Handler handler)
            throws ClassNotFoundException {
        Class<?> iface = Class.forName(interfaceName, false, loader);
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString": return "OShareAirDropCallback(" + interfaceName + ")";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return args != null && proxy == args[0];
                        default: return handler.invoke(method.getName(), args);
                    }
                });
    }
}
