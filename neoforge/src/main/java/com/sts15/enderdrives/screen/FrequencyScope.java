package com.sts15.enderdrives.screen;

public enum FrequencyScope {
    GLOBAL(0, "screen.enderdrives.scope.global"),
    PERSONAL(1, "screen.enderdrives.scope.private"),
    TEAM(2, "screen.enderdrives.scope.team");

    public final int id;
    public final String translationKey;

    FrequencyScope(int id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public static FrequencyScope fromId(int id) {
        return switch (id) {
            case 1 -> PERSONAL;
            case 2 -> TEAM;
            default -> GLOBAL;
        };
    }

    public String translationKey() {
        return translationKey;
    }
}
