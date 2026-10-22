package com.tlsu.oshareairdrop;
import android.os.SystemProperties;
import android.content.Context;
public final class ShareModeRuntimeTest {
 private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
  SystemProperties.set(ShareMode.PROPERTY,"quickshare");
  check(ShareModeRuntime.initialize(new Context())==ShareMode.QUICKSHARE,"initial mode");
  SystemProperties.set(ShareMode.PROPERTY,"oshare");
  check(ShareModeRuntime.current()==ShareMode.OSHARE,"mode cached until process restart");
  check(ShareModeRuntime.result("d")==1,"native send not isolated");
  ShareMode.bridgeCall(()->{check(ShareModeRuntime.result("d")==null,"bridge send blocked");return null;});
  SystemProperties.set("debug.opairshare.share_switching","1");
  ShareMode.bridgeCall(()->{check(ShareModeRuntime.result("d")==1,"new bridge work raced teardown");return null;});
  check(ShareModeRuntime.result("b")==1,"native task isolation lost");
  SystemProperties.set(ShareMode.PROPERTY,"both");
  check(ShareModeRuntime.result("a")==null,"existing invitation cannot be accepted");
  check(ShareModeRuntime.result("b")==null,"existing transfer cannot be cancelled");
  check(ShareModeRuntime.result("h")==null,"advertising cannot drain");
  SystemProperties.set("debug.opairshare.share_switching","0");
  check(ShareModeRuntime.result("d")==null,"coexistence did not resume");
  System.out.println("ShareModeRuntime passed: live updates, admission barrier, task draining and bridge ownership");
 }
}
