import android.content.Context;
import android.net.wifi.WifiManager;
import dalvik.system.PathClassLoader;
import java.lang.reflect.*;
import java.util.List;

public final class PolicySelftest {
    public static final class Target { public long a; Target(long v){a=v;} }
    public static final class Metadata { public boolean e=true; public int a=1001; }
    static void prop(String v) throws Exception {
        int rc=new ProcessBuilder("/system/bin/setprop","debug.opairshare.discovery",v).inheritIO().start().waitFor();
        if(rc!=0)throw new IllegalStateException("setprop failed");
    }
    public static void main(String[] args) throws Exception {
        Class<?> sp=Class.forName("android.os.SystemProperties");
        String original=(String)sp.getMethod("get",String.class,String.class).invoke(null,"debug.opairshare.discovery","");
        String standby=(String)sp.getMethod("get",String.class,String.class).invoke(null,"debug.opairshare.standby","");
        android.os.Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context context=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        Class<?> policy=new PathClassLoader(args[0],PolicySelftest.class.getClassLoader()).loadClass("dev.evermore.opairshare.DiscoveryPolicy");
        Method method=policy.getMethod("keepWifi",WifiManager.class,List.class);
        try {
            prop(""); if(!Boolean.FALSE.equals(method.invoke(null,wifi,List.of(149)))) throw new AssertionError("off");
            System.out.println("PASS default-off path");
            prop("cross5g"); if(!Boolean.TRUE.equals(method.invoke(null,wifi,List.of(149)))) throw new AssertionError("same149");
            System.out.println("PASS actual connected STA5200 retains Wi-Fi");
            boolean refused=false;
            try {method.invoke(null,wifi,List.of(6));} catch(InvocationTargetException e) {refused=e.getCause() instanceof IllegalStateException;}
            if(!refused)throw new AssertionError("unsupported request accepted");
            System.out.println("PASS unsupported channel refused before Wi-Fi mutation");
            if (!Boolean.TRUE.equals(method.invoke(null,wifi,List.of(149)))) throw new AssertionError("discovery revalidation");
            if (args.length > 1 && args[1].equals("--transfers")) {
                Method begin=policy.getMethod("beginTransfer",WifiManager.class,Object.class);
                Method update=policy.getMethod("onMetadata",Object.class,Object.class);
                Method active=policy.getMethod("isTransferActive");
                Target a=new Target(101),b=new Target(102),unowned=new Target(103);
                begin.invoke(null,wifi,a);begin.invoke(null,wifi,a);begin.invoke(null,wifi,b);
                if(!Boolean.TRUE.equals(active.invoke(null)))throw new AssertionError("no lease");
                update.invoke(null,unowned,new Metadata());
                update.invoke(null,a,new Metadata());
                if(!Boolean.TRUE.equals(active.invoke(null)))throw new AssertionError("other transfer lost");
                update.invoke(null,b,new Metadata());
                if(!Boolean.FALSE.equals(active.invoke(null)))throw new AssertionError("duplicate owner retained");
                begin.invoke(null,wifi,a);
                policy.getMethod("sendResult",Object.class,int.class).invoke(null,a,13);
                if(!Boolean.FALSE.equals(active.invoke(null)))throw new AssertionError("send rejection retained");
                System.out.println("PASS actual DEX duplicate/multiple owners, unrelated terminal, final release and rejected send");
                for(String name:new String[]{"cad","cen","aqr"}) {
                    Class.forName(name,false,policy.getClassLoader()).getDeclaredMethods();
                    System.out.println("LOADED patched class "+name);
                }
                if (args.length > 2 && args[2].equals("--standby")) {
                    Class<?> apj=policy.getClassLoader().loadClass("apj");
                    Method options=policy.getMethod("advertisingOptions",Object.class);
                    Method retain=policy.getMethod("retainAdvertising");
                    Object visible=apj.getConstructor(int.class,String.class,boolean.class,boolean.class).newInstance(0,"Stage66 test",true,false);
                    Object hidden=apj.getConstructor(int.class,String.class,boolean.class,boolean.class).newInstance(3,"",false,false);
                    if(options.invoke(null,hidden)!=hidden)throw new AssertionError("standby default not off");
                    Process p=new ProcessBuilder("/system/bin/setprop","debug.opairshare.standby","1").inheritIO().start();
                    if(p.waitFor()!=0)throw new IllegalStateException("standby setprop failed");
                    options.invoke(null,visible);
                    Object normalized=options.invoke(null,hidden);
                    if(apj.getField("a").getInt(normalized)!=0 || !apj.getField("c").getBoolean(normalized))throw new AssertionError("hidden retained");
                    if(!Boolean.TRUE.equals(retain.invoke(null)))throw new AssertionError("standby stop not owned");
                    begin.invoke(null,wifi,a);
                    if(!Boolean.FALSE.equals(retain.invoke(null)))throw new AssertionError("active transfer Stop suppressed");
                    update.invoke(null,a,new Metadata());
                    new ProcessBuilder("/system/bin/setprop","debug.opairshare.standby","").inheritIO().start().waitFor();
                    if(!Boolean.FALSE.equals(retain.invoke(null)))throw new AssertionError("standby opt-out failed");
                    System.out.println("PASS explicit standby ownership, lockscreen options, active-transfer Stop and opt-out");
                }
            }
        } finally {prop(original);new ProcessBuilder("/system/bin/setprop","debug.opairshare.standby",standby).inheritIO().start().waitFor();}
    }
}
