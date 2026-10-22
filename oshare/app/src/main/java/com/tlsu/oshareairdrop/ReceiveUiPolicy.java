package com.tlsu.oshareairdrop;

/** Completion is announced once on a live save transition, never on history replay. */
final class ReceiveUiPolicy {
    private ReceiveUiPolicy() {}

    static boolean showCompletion(boolean known, boolean wasPublished, boolean terminal, boolean published, String phase) {
        return known && !wasPublished && terminal && published && "complete".equals(phase);
    }

    static boolean hideDialog(boolean terminal, boolean nativeShown, boolean manual) {
        return !terminal && nativeShown && !manual;
    }
}
