package com.tlsu.oshareairdrop;
import android.content.*;
import android.os.Looper;
import android.graphics.Bitmap;
import org.json.JSONObject;
import java.util.*;
import java.lang.reflect.*;

public class AppearanceStateTest {
 public static class Device {
  public int type; public Bitmap bitmap;
  public void w0(int value){type=value;} public void t0(Bitmap value){bitmap=value;}
  public String a(){return "airdrop:778899";}
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");
  Object thread=at.getDeclaredMethod("systemMain").invoke(null);
  Context sys=(Context)at.getDeclaredMethod("getSystemContext").invoke(thread);
  Context raw=sys.createPackageContext("com.coloros.oshare",Context.CONTEXT_IGNORE_SECURITY);
  Map<String,String> saved=new HashMap<>();
  SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(self,m,a)->{
   if(m.getName().equals("putString")){saved.put((String)a[0],(String)a[1]);return self;}
   if(m.getName().equals("apply"))return null;
   if(m.getName().equals("commit"))return true;
   throw new AssertionError(m.getName());
  });
  SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(self,m,a)->{
   if(m.getName().equals("getString"))return saved.getOrDefault((String)a[0],(String)a[1]);
   if(m.getName().equals("edit"))return editor;
   throw new AssertionError(m.getName());
  });
  Context context=new ContextWrapper(raw){public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}};
  JSONObject target=new JSONObject().put("id",778899).put("name","Mimi").put("type",1);
  Device phone=new Device();OShareAppearance.apply(context,phone,target);
  check(phone.type==1 && phone.bitmap!=null,"PHONE icon not bound");
  Field kinds=OShareAppearance.class.getDeclaredField("KINDS");kinds.setAccessible(true);((Map)kinds.get(null)).clear();
  Device recreated=new Device();target.put("type",0);OShareAppearance.apply(context,recreated,target);
  check(recreated.type==1,"UNKNOWN after restart replaced known PHONE");
  recreated.bitmap=null;OShareAppearance.restore(context,recreated);
  check(recreated.bitmap!=null,"row rebind did not restore icon");
  target.put("type",2);OShareAppearance.apply(context,recreated,target);
  check(recreated.type==2,"known TABLET update ignored");
  recreated.bitmap.recycle();OShareAppearance.restore(context,recreated);
  check(!recreated.bitmap.isRecycled(),"recycled cached bitmap reused");
  System.out.println("ANDROID_ICON_STATE_PASSED: PHONE, UNKNOWN after restart, row rebind, known type update, recycled bitmap recovery; native resources");
  System.exit(0);
 }
}
