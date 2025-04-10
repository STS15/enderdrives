package com.sts15.enderdrives.client;

public class ClientConfigCache {
    private static int driveBitmask = 0x7F;
    public static int freqMin = 1;
    public static int freqMax = 4095;

    public static void update(int min, int max) {
        freqMin = min;
        freqMax = max;
    }

    public static void setDriveBitmask(int bitmask) {
        driveBitmask = bitmask;
    }

    public static boolean isDriveEnabled(int index) {
        return (driveBitmask & (1 << index)) != 0;
    }

    public static boolean isDriveDisabled(int index) {
        return !isDriveEnabled(index);
    }
}
