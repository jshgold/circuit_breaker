package com.circuitbreaker.sim;

final class SimUtil {

    private SimUtil() {
    }

    static void sleepMs(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
