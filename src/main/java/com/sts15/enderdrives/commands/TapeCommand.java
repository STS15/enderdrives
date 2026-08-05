package com.sts15.enderdrives.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.db.TapeDBManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /enderdrives tape ...
 */
public final class TapeCommand {

    private static final int MAX_TAPE_ENTRY_BYTES = 64 * 1024 * 1024;

    private TapeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("tape")
                .then(Commands.literal("release")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getActiveTapeIds().forEach(uuid -> builder.suggest(uuid.toString()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                    try {
                                        UUID uuid = UUID.fromString(uuidStr);
                                        if (!TapeDBManager.getActiveTapeIds().contains(uuid)) {
                                            source.sendFailure(Component.translatable("commands.enderdrives.tape.release.not_cached", uuid));
                                            return 0;
                                        }

                                        TapeDBManager.releaseFromRAM(uuid);
                                        if (TapeDBManager.getActiveTapeIds().contains(uuid)) {
                                            source.sendFailure(Component.translatable(
                                                    "commands.enderdrives.tape.release.fail", uuid));
                                            return 0;
                                        }
                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.release.success", uuid), true);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        source.sendFailure(Component.translatable("commands.enderdrives.tape.invalid_uuid", uuidStr));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            Set<UUID> cached = TapeDBManager.getActiveTapeIds();

                            if (cached.isEmpty()) {
                                source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.list.empty"), false);
                                return 1;
                            }

                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.list.header"), false);

                            for (UUID id : cached) {
                                int typeCount = TapeDBManager.getTypeCount(id);
                                source.sendSuccess(() ->
                                                Component.translatable("commands.enderdrives.tape.list.entry", id, typeCount),
                                        false
                                );
                            }

                            return 1;
                        })
                )
                .then(Commands.literal("export")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getActiveTapeIds().forEach(uuid -> builder.suggest(uuid.toString()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                    try {
                                        UUID uuid = UUID.fromString(uuidStr);
                                        boolean success = TapeDBManager.exportToJson(uuid);
                                        if (!success) {
                                            source.sendFailure(Component.translatable("commands.enderdrives.tape.export.fail"));
                                            return 0;
                                        }
                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.export.success", uuid), false);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        source.sendFailure(Component.translatable("commands.enderdrives.tape.invalid_uuid", uuidStr));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("import")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getActiveTapeIds().forEach(uuid -> builder.suggest(uuid.toString()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                    try {
                                        UUID uuid = UUID.fromString(uuidStr);
                                        boolean success = TapeDBManager.importFromJson(uuid);
                                        if (!success) {
                                            source.sendFailure(Component.translatable("commands.enderdrives.tape.import.fail"));
                                            return 0;
                                        }
                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.import.success", uuid), false);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        source.sendFailure(Component.translatable("commands.enderdrives.tape.invalid_uuid", uuidStr));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("oldest")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            List<File> files = TapeDBManager.getSortedBinFilesOldestFirst();
                            if (files.isEmpty()) {
                                source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.oldest.empty"), false);
                                return 1;
                            }

                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.oldest.header"), false);
                            for (File f : files) {
                                String name = f.getName().replace(".bin", "");
                                long lastMod = f.lastModified();
                                long size = f.length();
                                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastMod));
                                source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.oldest.entry", name, time, size), false);
                            }

                            return 1;
                        })
                )
                .then(Commands.literal("delete")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getSortedBinFilesOldestFirst().forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                    CommandSourceStack source = ctx.getSource();
                                    try {
                                        UUID uuid = UUID.fromString(uuidStr);
                                        if (TapeDBManager.getActiveTapeIds().contains(uuid)) {
                                            TapeDBManager.releaseFromRAM(uuid);
                                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.delete.released", uuid), false);
                                        }

                                        boolean success = TapeDBManager.deleteTape(uuid);
                                        if (!success) {
                                            source.sendFailure(Component.translatable("commands.enderdrives.tape.delete.fail", uuid));
                                            return 0;
                                        }

                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.delete.success", uuid), true);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        source.sendFailure(Component.translatable("commands.enderdrives.tape.invalid_uuid", uuidStr));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("diagnose")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getSortedBinFilesOldestFirst().forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");

                                    try {
                                        UUID uuid = UUID.fromString(uuidStr);
                                        File file = TapeDBManager.getDiskFile(uuid);
                                        if (!file.exists()) {
                                            source.sendFailure(Component.translatable("commands.enderdrives.tape.diagnose.no_file", uuid));
                                            return 0;
                                        }

                                        int total = 0;
                                        int failed = 0;
                                        long bytes = file.length();

                                        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                                            while (true) {
                                                int len = readEntryLength(dis);
                                                if (len < 0) break;
                                                byte[] data = new byte[len];
                                                dis.readFully(data);
                                                dis.readLong(); // count
                                                total++;

                                                var stack = com.sts15.enderdrives.items.TapeDiskItem.deserializeItemStackFromBytes(data);
                                                if (stack.isEmpty()) failed++;
                                            }
                                        } catch (IOException e) {
                                            source.sendFailure(Component.translatable("commands.enderdrives.tape.diagnose.scan_error", e.getMessage()));
                                            return 0;
                                        }

                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose.header", uuid), false);
                                        int finalTotal = total;
                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose.total", finalTotal), false);
                                        int finalFailed = failed;
                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose.malformed", finalFailed), false);
                                        source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose.size", bytes), false);

                                        if (failed > 0) {
                                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose.suggest", uuid), false);
                                        }

                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        source.sendFailure(Component.translatable("commands.enderdrives.tape.invalid_uuid", uuidStr));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("diagnose-all")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            List<File> files = TapeDBManager.getSortedBinFilesOldestFirst();
                            if (files.isEmpty()) {
                                source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose_all.empty"), false);
                                return 1;
                            }

                            int badCount = 0;
                            for (File file : files) {
                                UUID id = UUID.fromString(file.getName().replace(".bin", ""));
                                int total = 0;
                                int failed = 0;
                                try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                                    while (true) {
                                        int len = readEntryLength(dis);
                                        if (len < 0) break;
                                        byte[] data = new byte[len];
                                        dis.readFully(data);
                                        dis.readLong(); // count
                                        total++;
                                        var stack = com.sts15.enderdrives.items.TapeDiskItem.deserializeItemStackFromBytes(data);
                                        if (stack.isEmpty()) failed++;
                                    }
                                } catch (Exception e) {
                                    source.sendFailure(Component.translatable("commands.enderdrives.tape.diagnose_all.error", id, e.getMessage()));
                                    continue;
                                }

                                int finalTotal;
                                if (failed > 0) {
                                    badCount++;
                                    int finalFailed = failed;
                                    finalTotal = total;
                                    source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose_all.fail_entry", id, finalFailed, finalTotal), false);
                                } else {
                                    finalTotal = total;
                                    source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose_all.ok_entry", id, finalTotal), false);
                                }
                            }

                            int finalBadCount = badCount;
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.diagnose_all.summary", files.size(), finalBadCount), false);
                            return 1;
                        })
                )
                .then(Commands.literal("stats")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            Set<UUID> cached = TapeDBManager.getActiveTapeIds();
                            int cachedDrives = cached.size();
                            int totalTypes = 0;
                            long totalBytes = 0;

                            for (UUID id : cached) {
                                totalTypes += TapeDBManager.getTypeCount(id);
                                totalBytes += TapeDBManager.getTotalStoredBytes(id);
                            }

                            long totalFiles = TapeDBManager.getSortedBinFilesOldestFirst().stream().count();
                            long totalDiskSize = TapeDBManager.getSortedBinFilesOldestFirst().stream()
                                    .mapToLong(File::length).sum();

                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.stats.header"), false);
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.stats.cached", cachedDrives), false);
                            int finalTotalTypes = totalTypes;
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.stats.total_types", finalTotalTypes), false);
                            long finalTotalBytes = totalBytes;
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.stats.ram_usage", finalTotalBytes), false);
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.stats.file_count", totalFiles), false);
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.stats.disk_usage", totalDiskSize), false);

                            return 1;
                        })
                )
                .then(Commands.literal("cleanup-empty")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            List<File> files = TapeDBManager.getSortedBinFilesOldestFirst();
                            int removed = 0;

                            for (File file : files) {
                                UUID id = UUID.fromString(file.getName().replace(".bin", ""));
                                int total = 0;
                                try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                                    while (true) {
                                        int len = readEntryLength(dis);
                                        if (len < 0) break;
                                        dis.skipNBytes(len);
                                        dis.readLong();
                                        total++;
                                    }
                                } catch (Exception e) {
                                    source.sendFailure(Component.translatable("commands.enderdrives.tape.cleanup.error", id, e.getMessage()));
                                    continue;
                                }

                                if (total == 0) {
                                    if (TapeDBManager.getActiveTapeIds().contains(id)) {
                                        TapeDBManager.releaseFromRAM(id);
                                    }
                                    TapeDBManager.deleteTape(id);
                                    removed++;
                                }
                            }

                            int finalRemoved = removed;
                            source.sendSuccess(() -> Component.translatable("commands.enderdrives.tape.cleanup.success", finalRemoved), false);
                            return 1;
                        })
                )
                .then(Commands.literal("pin")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getActiveTapeIds().forEach(id -> builder.suggest(id.toString()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String uuidString = StringArgumentType.getString(ctx, "uuid");
                                    try {
                                        UUID uuid = UUID.fromString(uuidString);
                                        TapeDBManager.pin(uuid);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.pin.success", uuid), true);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "commands.enderdrives.tape.invalid_uuid", uuidString));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("unpin")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getPinnedTapes().forEach(id -> builder.suggest(id.toString()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String uuidString = StringArgumentType.getString(ctx, "uuid");
                                    try {
                                        UUID uuid = UUID.fromString(uuidString);
                                        TapeDBManager.unpin(uuid);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.unpin.success", uuid), true);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "commands.enderdrives.tape.invalid_uuid", uuidString));
                                        return 0;
                                    }
                                })
                        )
                )
                .then(Commands.literal("info")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    TapeDBManager.getSortedBinFilesOldestFirst().forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String uuidString = StringArgumentType.getString(ctx, "uuid");
                                    try {
                                        UUID uuid = UUID.fromString(uuidString);
                                        var cache = TapeDBManager.getCache(uuid);
                                        boolean cached = cache != null;
                                        int typeCount = TapeDBManager.getTypeCount(uuid);
                                        long byteSize = TapeDBManager.getTotalStoredBytes(uuid);
                                        boolean pinned = TapeDBManager.isPinned(uuid);
                                        long lastAccessed = cached ? cache.lastAccessed : -1;
                                        String accessed = lastAccessed > 0
                                                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastAccessed))
                                                : Component.translatable("commands.enderdrives.tape.info.not_in_ram").getString();
                                        String inRam = Component.translatable(cached ? "commands.enderdrives.common.yes" : "commands.enderdrives.common.no").getString();
                                        String pinnedText = Component.translatable(pinned ? "commands.enderdrives.common.yes" : "commands.enderdrives.common.no").getString();

                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.info.header", uuid), false);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.info.in_ram", inRam), false);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.info.pinned", pinnedText), false);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.info.types", typeCount), false);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.info.bytes", byteSize), false);
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.enderdrives.tape.info.last_accessed", accessed), false);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        ctx.getSource().sendFailure(Component.translatable(
                                                "commands.enderdrives.tape.invalid_uuid", uuidString));
                                        return 0;
                                    }
                                })
                        )
                );
    }

    private static int readEntryLength(DataInputStream input) throws IOException {
        int firstByte = input.read();
        if (firstByte < 0) return -1;
        int length = (firstByte << 24)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 8)
                | input.readUnsignedByte();
        if (length <= 0 || length > MAX_TAPE_ENTRY_BYTES) {
            throw new IOException("Invalid tape entry length: " + length);
        }
        return length;
    }
}
