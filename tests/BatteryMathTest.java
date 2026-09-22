package com.rawal.pocketdeck;
public class BatteryMathTest {
    static void equal(double actual, double expected) {
        if (Math.abs(actual - expected) > 0.001) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        equal(BatteryMath.watts(1250000, 4000), 5);
        equal(BatteryMath.watts(-800000, 4000), -3.2);
        if (!Double.isNaN(BatteryMath.watts(Integer.MIN_VALUE, 4000))) throw new AssertionError("Unsupported current");
        if (!Double.isNaN(BatteryMath.watts(1000000, -1))) throw new AssertionError("Missing voltage");
        equal(BatteryMath.remaining(50, 2000000, 1000000, true), 7200000);
        equal(BatteryMath.remaining(50, 2000000, -500000, false), 14400000);
        equal(BatteryMath.remaining(100, 4000000, 1000000, true), 0);
        equal(BatteryMath.remaining(0, 1, 1000000, true), -1);
        equal(BatteryMath.remaining(50, 2000000, Integer.MIN_VALUE, false), -1);
        equal(BatteryMath.remaining(50, 2000000, -500000, true), -1);
        equal(BatteryMath.remaining(50, 2000000, 0, false), -1);
        System.out.println("PASS: watts units, charge/discharge estimates, unsupported sensors and invalid direction");
    }
}
