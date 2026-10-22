package com.tlsu.oshareairdrop;

public final class ReceiveUiPolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(ReceiveUiPolicy.showCompletion(true, false, true, true, "complete"));
        check(!ReceiveUiPolicy.showCompletion(false, false, true, true, "complete")); // restored history
        check(!ReceiveUiPolicy.showCompletion(true, true, true, true, "complete")); // duplicate snapshot
        check(!ReceiveUiPolicy.showCompletion(true, false, false, false, "publishing")); // 100% before save
        check(!ReceiveUiPolicy.showCompletion(true, false, true, false, "failed"));
        check(ReceiveUiPolicy.hideDialog(false, true, false));
        check(!ReceiveUiPolicy.hideDialog(true, true, false)); // late native callback cannot close result
        check(!ReceiveUiPolicy.hideDialog(false, true, true)); // explicit details stay open
        check(!ReceiveUiPolicy.hideDialog(false, false, false)); // ROM fallback
        System.out.println("Receive UI: live completion, replay, save failure, native progress and manual details passed");
    }
}
