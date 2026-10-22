package com.tlsu.oshareairdrop;

/** One UI lifetime per submission, independent of the share Activity and peer discovery. */
final class SendUiState {
    final String session;
    int status, progress;
    boolean terminal, cancelling;
    SendUiState(String session) { this.session = session; }
    boolean matches(String token) { return session.equals(token); }
    boolean update(String token, int value, double percent, boolean finalFlag) {
        if (!matches(token) || terminal) return false;
        status = value;
        progress = value == 1006 ? 100 : (int) OutgoingTransfers.progress(percent);
        terminal = OutgoingTransfers.isTerminal(value, finalFlag);
        return true;
    }
    boolean submitted() {
        if (status != 0 || terminal) return false;
        status = 1001;
        return true;
    }
    String label() {
        if (terminal) {
            switch (status) {
                case 1006: return "发送完成";
                case 1008: return "对方已拒绝";
                case 1009: case 1018: return "已取消";
                case 1010: return "发送超时";
                case 1013: return "对方空间不足";
                default: return "发送失败";
            }
        }
        if (cancelling) return "正在取消";
        String pending = AirDropPresentation.pendingLabel(status);
        if (pending != null) return pending;
        return status == 1005 ? "正在发送 " + progress + "%" : "正在准备发送";
    }
    String capsule() { return status == 1005 && !terminal && !cancelling ? progress + "%" : label(); }
}
