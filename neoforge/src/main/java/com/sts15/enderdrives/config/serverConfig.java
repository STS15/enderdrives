package com.sts15.enderdrives.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public class serverConfig {

    public static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    // === General Toggles ===
    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_DESC_GENERAL = "Enable/disable individual drive types.";

    public static ModConfigSpec.BooleanValue ENDER_DISK_1K_TOGGLE;
    public static ModConfigSpec.BooleanValue ENDER_DISK_4K_TOGGLE;
    public static ModConfigSpec.BooleanValue ENDER_DISK_16K_TOGGLE;
    public static ModConfigSpec.BooleanValue ENDER_DISK_64K_TOGGLE;
    public static ModConfigSpec.BooleanValue ENDER_DISK_256K_TOGGLE;
    public static ModConfigSpec.BooleanValue ENDER_DISK_CREATIVE_TOGGLE;
    public static ModConfigSpec.BooleanValue TAPE_DISK_TOGGLE;

    // === Ender Disk Configs ===
    public static final String CATEGORY_ENDERDISK = "ender_disk";
    public static final String CATEGORY_DESC_ENDERDISK = "Configuration for Ender Drive item type limits.";

    public static ModConfigSpec.IntValue ENDER_DISK_1K_TYPE_LIMIT;
    public static ModConfigSpec.IntValue ENDER_DISK_4K_TYPE_LIMIT;
    public static ModConfigSpec.IntValue ENDER_DISK_16K_TYPE_LIMIT;
    public static ModConfigSpec.IntValue ENDER_DISK_64K_TYPE_LIMIT;
    public static ModConfigSpec.IntValue ENDER_DISK_256K_TYPE_LIMIT;
    public static ModConfigSpec.IntValue ENDER_DISK_CREATIVE_TYPE_LIMIT;

    // === Ender Drive Scope Configs ===
    public static final String CATEGORY_SCOPE = "ender_scope";
    public static final String CATEGORY_DESC_SCOPE = "Controls which network scopes are allowed on ender drives.";

    public static ModConfigSpec.ConfigValue<String> ENDER_DRIVE_DEFAULT_SCOPE;
    public static ModConfigSpec.BooleanValue SCOPE_GLOBAL_ENABLED;
    public static ModConfigSpec.BooleanValue SCOPE_PRIVATE_ENABLED;
    public static ModConfigSpec.BooleanValue SCOPE_TEAM_ENABLED;

    // === Frequency Range ===
    public static ModConfigSpec.IntValue FREQ_MIN;
    public static ModConfigSpec.IntValue FREQ_MAX;

    // === EnderDB (Database) Configs ===
    public static final String CATEGORY_ENDERDB = "ender_db";
    public static final String CATEGORY_DESC_ENDERDB = "Configuration for the EnderDB database parameters.";

    public static ModConfigSpec.IntValue END_DB_MERGE_BUFFER_THRESHOLD;
    public static ModConfigSpec.IntValue END_DB_MIN_COMMIT_INTERVAL_MS;
    public static ModConfigSpec.IntValue END_DB_MAX_COMMIT_INTERVAL_MS;
    public static ModConfigSpec.IntValue END_DB_MIN_DB_COMMIT_INTERVAL_MS;
    public static ModConfigSpec.IntValue END_DB_MAX_DB_COMMIT_INTERVAL_MS;
    public static ModConfigSpec.BooleanValue END_DB_DEBUG_LOG;


    // === Tape Disk Configs ===
    public static final String CATEGORY_TAPEDISK = "tape_disk";
    public static final String CATEGORY_DESC_TAPEDISK = "Configuration for tape disk cold storage drives.";

    public static ModConfigSpec.IntValue TAPE_DISK_TYPE_LIMIT;
    public static ModConfigSpec.IntValue TAPE_DISK_BYTE_LIMIT;
    public static ModConfigSpec.IntValue TAPE_DB_FLUSH_THRESHOLD;
    public static ModConfigSpec.IntValue TAPE_DB_FLUSH_INTERVAL;
    public static ModConfigSpec.IntValue TAPE_DB_RAM_EVICT_TIMEOUT;
    public static ModConfigSpec.BooleanValue TAPE_DB_DEBUG_LOG;

    // === EnderDrive Command Configs ===
    public static final String CATEGORY_COMMANDS = "ender_commands";
    public static final String CATEGORY_DESC_COMMANDS = "Settings for autobenchmark.";

    public static ModConfigSpec.IntValue AUTO_BENCHMARK_INITIAL_SIZE;
    public static ModConfigSpec.IntValue AUTO_BENCHMARK_MAX_TYPES;
    public static ModConfigSpec.IntValue AUTO_BENCHMARK_STEP;
    public static ModConfigSpec.IntValue AUTO_BENCHMARK_MS_SLEEP;
    public static ModConfigSpec.DoubleValue AUTO_BENCHMARK_MIN_TPS;


    public static void register(ModContainer container) {
        generalToggleConfig();
        enderScopeConfig();
        enderDiskTypeLimits();
        tapeDiskConfig();
        enderDBConfig();
        enderCommandConfig();
        container.registerConfig(ModConfig.Type.SERVER, SERVER_BUILDER.build());
    }

    private static void generalToggleConfig() {
        SERVER_BUILDER.comment(CATEGORY_DESC_GENERAL).push(CATEGORY_GENERAL);
        ENDER_DISK_1K_TOGGLE = SERVER_BUILDER.define("enable_ender_disk_1k", true);
        ENDER_DISK_4K_TOGGLE = SERVER_BUILDER.define("enable_ender_disk_4k", true);
        ENDER_DISK_16K_TOGGLE = SERVER_BUILDER.define("enable_ender_disk_16k", true);
        ENDER_DISK_64K_TOGGLE = SERVER_BUILDER.define("enable_ender_disk_64k", true);
        ENDER_DISK_256K_TOGGLE = SERVER_BUILDER.define("enable_ender_disk_256k", true);
        ENDER_DISK_CREATIVE_TOGGLE = SERVER_BUILDER.define("enable_ender_disk_creative", true);
        TAPE_DISK_TOGGLE = SERVER_BUILDER.define("enable_tape_disk", true);
        SERVER_BUILDER.pop();
    }

    private static void enderDiskTypeLimits() {
        SERVER_BUILDER.comment(CATEGORY_DESC_ENDERDISK).push(CATEGORY_ENDERDISK);

        ENDER_DISK_1K_TYPE_LIMIT = SERVER_BUILDER
                .comment("Max item types for Ender Disk 1k")
                .defineInRange("type_limit_1k", 1, 1, 1024);

        ENDER_DISK_4K_TYPE_LIMIT = SERVER_BUILDER
                .comment("Max item types for Ender Disk 4k")
                .defineInRange("type_limit_4k", 31, 1, 1024);

        ENDER_DISK_16K_TYPE_LIMIT = SERVER_BUILDER
                .comment("Max item types for Ender Disk 16k")
                .defineInRange("type_limit_16k", 63, 1, 1024);

        ENDER_DISK_64K_TYPE_LIMIT = SERVER_BUILDER
                .comment("Max item types for Ender Disk 64k")
                .defineInRange("type_limit_64k", 127, 1, 1024);

        ENDER_DISK_256K_TYPE_LIMIT = SERVER_BUILDER
                .comment("Max item types for Ender Disk 256k")
                .defineInRange("type_limit_256k", 255, 1, 1024);

        ENDER_DISK_CREATIVE_TYPE_LIMIT = SERVER_BUILDER
                .comment("Max item types for Creative Ender Disk")
                .defineInRange("type_limit_creative", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

        SERVER_BUILDER.pop();
    }

    private static void enderScopeConfig() {
        SERVER_BUILDER.comment(CATEGORY_DESC_SCOPE).push(CATEGORY_SCOPE);

        ENDER_DRIVE_DEFAULT_SCOPE = SERVER_BUILDER
                .comment("Default scope used for new Ender Drives. Options: global, private, team")
                .define("default_scope", "global");

        SCOPE_GLOBAL_ENABLED = SERVER_BUILDER
                .comment("Enable global scope")
                .define("enable_scope_global", true);

        SCOPE_PRIVATE_ENABLED = SERVER_BUILDER
                .comment("Enable private scope")
                .define("enable_scope_private", true);

        SCOPE_TEAM_ENABLED = SERVER_BUILDER
                .comment("Enable team scope")
                .define("enable_scope_team", true);

        FREQ_MIN = SERVER_BUILDER
                .comment("Minimum allowed frequency value (Must be <= than max)")
                .defineInRange("frequency_min", 0, 0, 4095);

        FREQ_MAX = SERVER_BUILDER
                .comment("Maximum allowed frequency value")
                .defineInRange("frequency_max", 4095, 1, 4095);

        SERVER_BUILDER.pop();
    }

    private static void enderDBConfig() {
        SERVER_BUILDER.comment(CATEGORY_DESC_ENDERDB).push(CATEGORY_ENDERDB);
        END_DB_MERGE_BUFFER_THRESHOLD = SERVER_BUILDER
                .comment("Number of pending WAL entries before merging")
                .defineInRange("merge_buffer_threshold", 1000, 1, 10_000);
        END_DB_MIN_COMMIT_INTERVAL_MS = SERVER_BUILDER
                .comment("Minimum time (ms) between WAL commits")
                .defineInRange("min_commit_interval_ms", 2500, 500, 60000);
        END_DB_MAX_COMMIT_INTERVAL_MS = SERVER_BUILDER
                .comment("Maximum time (ms) between WAL commits")
                .defineInRange("max_commit_interval_ms", 60000, 500, 60000);
        END_DB_MIN_DB_COMMIT_INTERVAL_MS = SERVER_BUILDER
                .comment("Minimum time (ms) between DB flush commits")
                .defineInRange("min_db_commit_interval_ms", 5000, 500, 60000);
        END_DB_MAX_DB_COMMIT_INTERVAL_MS = SERVER_BUILDER
                .comment("Maximum time (ms) between DB flush commits")
                .defineInRange("max_db_commit_interval_ms", 60000, 500, 60000);
        END_DB_DEBUG_LOG = SERVER_BUILDER
                .comment("Enable EXTREMELY verbose debug logging for EnderDB")
                .define("debug_log", false);
        SERVER_BUILDER.pop();
    }

    private static void tapeDiskConfig() {
        SERVER_BUILDER.comment(CATEGORY_DESC_TAPEDISK).push(CATEGORY_TAPEDISK);

        TAPE_DISK_TYPE_LIMIT = SERVER_BUILDER
                .comment("Maximum number of item types allowed in a tape disk (default: 100)")
                .defineInRange("tape_disk_type_limit", 255, 1, 1024000);

        TAPE_DISK_BYTE_LIMIT = SERVER_BUILDER
                .comment("Maximum number of item bytes allowed in a tape disk (default: 5MiB)")
                .defineInRange("tape_disk_max_bytes", 292144, 1024, 256 * 1024 * 1024);

        TAPE_DB_FLUSH_THRESHOLD = SERVER_BUILDER
                .comment("Number of changes before flushing deltaBuffer to disk")
                .defineInRange("flush_threshold", 500, 1, 10_000);

        TAPE_DB_FLUSH_INTERVAL = SERVER_BUILDER
                .comment("Interval (ms) between scheduled flushes to disk")
                .defineInRange("flush_interval", 5000, 500, 600_000);

        TAPE_DB_RAM_EVICT_TIMEOUT = SERVER_BUILDER
                .comment("Milliseconds after last access before a disk is evicted from RAM")
                .defineInRange("ram_eviction_timeout", 300_000, 60_000, 3_600_000);

        TAPE_DB_DEBUG_LOG = SERVER_BUILDER
                .comment("Enable EXTREMELY verbose debug logging for TapeDB")
                .define("debug_log", false);

        SERVER_BUILDER.pop();
    }

    private static void enderCommandConfig() {
        SERVER_BUILDER.comment(CATEGORY_DESC_COMMANDS).push(CATEGORY_COMMANDS);

        AUTO_BENCHMARK_INITIAL_SIZE = SERVER_BUILDER
                .comment("Initial test type count (default: 10,000)")
                .defineInRange("autobenchmark_initial_size", 10_000, 1_000, 1_000_000);

        AUTO_BENCHMARK_MAX_TYPES = SERVER_BUILDER
                .comment("Maximum number of entries attempted during autobenchmark (default: 2,000,000)")
                .defineInRange("autobenchmark_max_types", 2_000_000, 1_000, 10_000_000);

        AUTO_BENCHMARK_STEP = SERVER_BUILDER
                .comment("Step size of entries per iteration during autobenchmark (default: 5000)")
                .defineInRange("autobenchmark_step", 5000, 100, 100_000);

        AUTO_BENCHMARK_MS_SLEEP = SERVER_BUILDER
                .comment("Duration of rest between each step in ms (default: 10000)")
                .defineInRange("autobenchmark_ms_sleep", 10000, 100, 100_000);

        AUTO_BENCHMARK_MIN_TPS = SERVER_BUILDER
                .comment("Minimum TPS to maintain during autobenchmark (default: 18.0)")
                .defineInRange("autobenchmark_min_tps", 18.0, 1.0, 20.0);

        SERVER_BUILDER.pop();
    }

}
