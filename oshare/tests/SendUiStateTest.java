package com.tlsu.oshareairdrop;

public final class SendUiStateTest {
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        SendUiState task = new SendUiState("new-session");
        check(task.label().equals("正在准备发送"), "staging label");
        check(!task.update("old-session", 1006, 100, true), "old callback changed new task");
        check(task.submitted(), "submission not shown");
        check(task.label().equals("正在连接对方"), "submission claimed receiver invitation");
        task.update(task.session, 1003, 0, false);
        check(task.label().equals("等待对方接收"), "waiting label");
        task.update(task.session, 1005, 42.9, false);
        check(task.capsule().equals("42%"), "progress");
        check(!task.submitted() && task.status == 1005, "late submission regressed progress");
        task.cancelling = true;
        check(task.capsule().equals("正在取消"), "cancel state");
        task.update(task.session, 1006, 99, false);
        check(task.terminal && task.progress == 100 && task.label().equals("发送完成"), "success normalization");
        check(!task.update(task.session, 1005, 20, false), "terminal task revived");
        check(!task.submitted(), "late submission revived result");
        int[] statuses = {1008,1009,1018,1010,1013,1007};
        String[] labels = {"对方已拒绝","已取消","已取消","发送超时","对方空间不足","发送失败"};
        for (int i = 0; i < statuses.length; i++) {
            SendUiState result = new SendUiState("result");
            result.update("result", statuses[i], Double.NaN, false);
            check(result.terminal && result.label().equals(labels[i]), "terminal mapping " + statuses[i]);
        }
        SendUiState unknown = new SendUiState("unknown");
        unknown.update("unknown", 9999, Double.POSITIVE_INFINITY, true);
        check(unknown.terminal && unknown.progress == 0 && unknown.label().equals("发送失败"), "unknown final status");
        SendUiState other = new SendUiState("other-device");
        other.update("other-device", 1005, 23, false);
        check(!other.terminal && task.terminal, "concurrent UI states mixed");
        System.out.println("SendUiState: phases, stale/late events, cancel, result and concurrent task regressions passed");
    }
}
