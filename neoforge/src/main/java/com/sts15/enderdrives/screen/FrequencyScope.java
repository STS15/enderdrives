package com.sts15.enderdrives.screen;

public enum FrequencyScope {
    GLOBAL(0, "Global"),
    PRIVATE(1, "Private"),
    TEAM(2, "Team");

    public final int id;
    public final String label;

    FrequencyScope(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public static FrequencyScope fromId(int id) {
        for (FrequencyScope s : values()) if (s.id == id) return s;
        return GLOBAL;
    }
}
