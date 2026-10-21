import java.lang.reflect.*;
import java.util.*;

/** Executes the actual patched DEX in a separate app_process, never in Mosey. */
public final class OPAirShareCallbackSelftest {
    static Object unsafe;
    static Method allocate;
    static final List<String> events = new ArrayList<>();
    static int checks;
    static Class<?> cls(String name) throws Exception { return Class.forName(name); }
    static Object raw(String name) throws Exception { return allocate.invoke(unsafe, cls(name)); }
    static void set(Object o, String name, Object value) throws Exception {
        Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o,value);
    }
    static Object get(Object o, String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name + " events=" + events);
        checks++; System.out.println("PASS " + name);
    }
    static Object unit() throws Exception { return cls("doq").getField("a").get(null); }
    static Object callback(String tag) throws Exception {
        return Proxy.newProxyInstance(cls("dsc").getClassLoader(), new Class<?>[]{cls("dsc")},
            (proxy,method,args) -> {
                if (method.getName().equals("a")) {
                    events.add(tag+":"+get(args[1],"a")); return unit();
                }
                if (method.getName().equals("toString")) return tag;
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy==args[0];
                throw new AssertionError(method.toString());
            });
    }
    static Object metadata(int status, boolean terminal) throws Exception {
        Object m=raw("com.google.android.gms.nearby.sharing.TransferMetadata");
        set(m,"a",status); set(m,"e",terminal); return m;
    }
    static Object envelope(Object metadata) throws Exception {
        return cls("buw").getConstructor(cls("com.google.android.gms.nearby.sharing.ShareTarget"),
                metadata.getClass()).newInstance(null,metadata);
    }
    static Object collector(Object callback, Object owner) throws Exception {
        return cls("ecj").getConstructor(cls("dsc"),cls("cen"),int.class)
                .newInstance(callback,owner,1);
    }
    static void forward(Object collector, Object envelope) throws Exception {
        cls("ecj").getMethod("cp",Object.class,cls("dqd")).invoke(collector,envelope,null);
    }
    public static void main(String[] args) throws Exception {
        Field singleton=cls("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        singleton.setAccessible(true); unsafe=singleton.get(null);
        allocate=unsafe.getClass().getMethod("allocateInstance",Class.class);
        check(Boolean.TRUE.equals(cls("dly").getMethod("g").invoke(null)),"per-transfer branch active");
        Object a=callback("A"), b=callback("B"), owner=raw("cen");
        set(owner,"n",a);
        Object ca=collector(a,owner), cb=collector(b,owner);
        Object progress=envelope(metadata(1005,false)), complete=envelope(metadata(1006,true));
        forward(ca,progress);
        set(owner,"n",null); // The real advertising Stop clears this shared field.
        forward(ca,progress);
        check(events.equals(Arrays.asList("A:1005","A:1005")),"progress survives shared callback clear");
        set(owner,"n",b); // A new advertising session must not steal A's updates.
        forward(ca,progress); forward(cb,progress); forward(ca,complete);
        check(events.equals(Arrays.asList("A:1005","A:1005","A:1005","B:1005","A:1006")),
              "two transfers remain isolated through terminal delivery");

        Object transfer=cls("ccu").getConstructors()[0].newInstance(7L,raw("ccw"),raw("buo"),
                raw("ccs"),true,0,metadata(1005,false),a);
        check(cls("ccu").getMethod("opairshareTransferCallback").invoke(transfer)==a,"transfer owns original callback");
        Object copy=cls("ccu").getMethod("d",cls("ccu"),cls("cct"),
                cls("com.google.android.gms.nearby.sharing.TransferMetadata"),int.class)
                .invoke(null,transfer,null,metadata(1006,true),175);
        check(cls("ccu").getMethod("opairshareTransferCallback").invoke(copy)==a,"metadata copy retains callback");

        // Exercise cen.l's successful queue path with the real collector.
        Object channel=Proxy.newProxyInstance(cls("eao").getClassLoader(),new Class<?>[]{cls("eao")},
            (proxy,m,values) -> {
                if (m.getName().equals("j")) { forward(ca,values[0]); return unit(); }
                throw new AssertionError(m.toString());
            });
        Object data=raw("ccq");set(data,"e",channel);
        Object shareData=get(transfer,"c");set(shareData,"b",data);
        Method report=cls("cen").getMethod("l",cls("ccu"),cls("buw"),cls("dqd"));
        int before=events.size();report.invoke(owner,transfer,progress,null);
        check(events.size()==before+1 && events.get(before).equals("A:1005"),"real report-to-channel path uses A after owner changed to B");

        // Resume the real coroutine after a failed terminal retry. p1 is null on
        // resume, so only a correctly spilled per-transfer callback can work.
        Constructor<?> ctor=cls("cee").getDeclaredConstructor(cls("cen"),cls("dqd"));
        ctor.setAccessible(true);Object continuation=ctor.newInstance(owner,null);
        set(continuation,"c",Integer.MIN_VALUE|1);set(continuation,"a",Boolean.FALSE);
        set(continuation,"d",complete);set(continuation,"opairshareCallback",a);
        before=events.size();report.invoke(owner,null,null,continuation);
        check(events.size()==before+1 && events.get(before).equals("A:1006"),"resumed terminal fallback retains A after owner changed to B");
        System.out.println("SELFTEST_OK checks="+checks+" events="+events);
    }
}
