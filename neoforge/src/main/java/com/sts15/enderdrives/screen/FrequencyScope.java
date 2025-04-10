package com.sts15.enderdrives.screen;

import com.sts15.enderdrives.config.serverConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    public String translationKey() {
        return translationKey;
    }

    public static FrequencyScope fromId(int id) {
        for (FrequencyScope scope : values()) {
            if (scope.id == id && scope.isEnabled()) {
                return scope;
            }
        }
        return getDefault(); // fallback
    }

    public boolean isEnabled() {
        return switch (this) {
            case GLOBAL -> serverConfig.SCOPE_GLOBAL_ENABLED.get();
            case PERSONAL -> serverConfig.SCOPE_PRIVATE_ENABLED.get();
            case TEAM -> serverConfig.SCOPE_TEAM_ENABLED.get();
        };
    }

    public static FrequencyScope getDefault() {
        String configValue = serverConfig.ENDER_DRIVE_DEFAULT_SCOPE.get().toLowerCase(Locale.ROOT);
        return switch (configValue) {
            case "private", "personal" -> PERSONAL;
            case "team" -> TEAM;
            default -> GLOBAL;
        };
    }

    public static List<FrequencyScope> getEnabledScopes() {
        return Arrays.stream(values())
                .filter(FrequencyScope::isEnabled)
                .toList();
    }

    public int getId() {
        return this.id;
    }

}
