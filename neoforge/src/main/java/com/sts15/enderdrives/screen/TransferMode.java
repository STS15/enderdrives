package com.sts15.enderdrives.screen;

public class TransferMode {
    public static final int BIDIRECTIONAL = 0;
    public static final int INPUT_ONLY = 1;
    public static final int OUTPUT_ONLY = 2;

    public static int next(int mode) {
        return (mode + 1) % 3;
    }

    public static String getTranslationKey(int mode) {
        return switch (mode) {
            case INPUT_ONLY -> "enderdrives.transfer_mode.input";
            case OUTPUT_ONLY -> "enderdrives.transfer_mode.output";
            default -> "enderdrives.transfer_mode.bidirectional";
        };
    }
}
