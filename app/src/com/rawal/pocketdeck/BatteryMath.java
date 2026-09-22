package com.rawal.pocketdeck;

final class BatteryMath {
    static boolean currentAvailable(int current) { return current != Integer.MIN_VALUE; }
    static double watts(int microamps, int millivolts) {
        return currentAvailable(microamps) && millivolts > 0 ? microamps * (double) millivolts / 1e9 : Double.NaN;
    }
    static long remaining(int level, int chargeMicroAh, int averageMicroA, boolean charging) {
        if (level <= 0 || level > 100 || chargeMicroAh <= 0 || !currentAvailable(averageMicroA)
                || Math.abs((long) averageMicroA) < 10000 || (charging ? averageMicroA <= 0 : averageMicroA >= 0)) return -1;
        double amount = charging ? chargeMicroAh * (100.0 - level) / level : chargeMicroAh;
        double ms = amount / Math.abs((double) averageMicroA) * 3600000;
        return Double.isFinite(ms) && ms <= 7 * 86400000L ? Math.round(ms) : -1;
    }
}
