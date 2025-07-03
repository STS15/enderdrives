package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import com.sts15.enderdrives.config.serverConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

import static com.sts15.enderdrives.items.TapeDiskItem.deserializeItemStackFromBytes;

public class TapeDBManager {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives-TapeDB");
    private static final Map<UUID, TapeDriveCache> activeCaches = new ConcurrentHashMap<>();
    private static final Set<UUID> pinnedTapes = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static final int FLUSH_THRESHOLD = serverConfig.TAPE_DB_FLUSH_THRESHOLD.get();
    private static final long FLUSH_INTERVAL = serverConfig.TAPE_DB_FLUSH_INTERVAL.get();
    private static final long EVICTION_THRESHOLD = serverConfig.TAPE_DB_RAM_EVICT_TIMEOUT.get();
    private static final int PAGE_SIZE = 10000;
    private static final double BYTE_COST_MULTIPLIER = 0.75;
    static boolean debug_log = serverConfig.TAPE_DB_DEBUG_LOG.get();

    public static void init() {
        if (executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(TapeDBManager::flushAll));
        executor.scheduleAtFixedRate(TapeDBManager::flushAndEvict, FLUSH_INTERVAL, FLUSH_INTERVAL, TimeUnit.MILLISECONDS);
        log("TapeDBManager initialized.");
    }

    public static TapeDriveCache getCache(UUID diskId) {
        return activeCaches.get(diskId);
    }

    public static TapeDriveCache getCacheSafe(UUID diskId) {
        return activeCaches.get(diskId);
    }

    public static TapeDriveCache getOrLoadForRead(UUID diskId) {
        return activeCaches.computeIfAbsent(diskId, TapeDBManager::loadFromDisk);
    }

    public static CompletableFuture<TapeDriveCache> loadFromDiskAsync(UUID diskId) {
        if (executor.isShutdown() || executor.isTerminated()) {
            init();
        }
        return CompletableFuture.supplyAsync(() -> {
            TapeDriveCache cache = loadFromDisk(diskId);
            activeCaches.put(diskId, cache);
            notifyAE2StorageChanged(diskId);
            return cache;
        }, executor);
    }


    private static void notifyAE2StorageChanged(UUID diskId) {
        // TODO: AE2 storage refresh hook (if needed)
    }

    public static long getItemCount(UUID diskId, byte[] itemBytes) {
        TapeDriveCache cache = getOrLoadForRead(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        TapeKey key = new TapeKey(itemBytes);
        long committed = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
        long delta = cache.deltaBuffer.getOrDefault(key, 0L);
        return Math.max(0, committed + delta);
    }

    public static int getTypeCount(UUID diskId) {
        TapeDriveCache cache = getOrLoadForRead(diskId);
        cache.lastAccessed = System.currentTimeMillis();

        Set<TapeKey> keys = new HashSet<>();
        keys.addAll(cache.entries.keySet());
        keys.addAll(cache.deltaBuffer.keySet());

        int count = 0;
        for (TapeKey key : keys) {
            long base = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
            long delta = cache.deltaBuffer.getOrDefault(key, 0L);
            if (base + delta > 0) count++;
        }
        return count;
    }

    public static long getTotalStoredBytes(UUID diskId) {
        TapeDriveCache cache = getCache(diskId);
        if (cache == null) return 0L;
        long total = 0L;
        for (Map.Entry<TapeKey, StoredEntry> entry : cache.entries.entrySet()) {
            long count = entry.getValue().count();
            total += Math.round(entry.getKey().itemBytes().length * count * BYTE_COST_MULTIPLIER);
        }
        for (Map.Entry<TapeKey, Long> entry : cache.deltaBuffer.entrySet()) {
            long count = entry.getValue();
            total += Math.round(entry.getKey().itemBytes().length * count * BYTE_COST_MULTIPLIER);
        }
        return Math.max(0, total);
    }

    public static long getByteLimit(UUID diskId) {
        return serverConfig.TAPE_DISK_BYTE_LIMIT.get();
    }

    public static void releaseFromRAM(UUID id) {
        TapeDriveCache cache = activeCaches.remove(id);
        if (cache != null) {
            flushAndSave(id, cache);
            log("Released and saved tape {} from RAM", id);
        }
    }

    public static Set<UUID> getActiveTapeIds() {
        return new HashSet<>(activeCaches.keySet());
    }

    public static void saveItem(UUID diskId, byte[] itemBytes, AEItemKey key, long delta) {
        TapeDriveCache cache = getCache(diskId);
        if (cache == null) return;

        TapeKey tapeKey = new TapeKey(itemBytes);
        cache.lastAccessed = System.currentTimeMillis();

        // Apply delta to deltaBuffer
        cache.deltaBuffer.merge(tapeKey, delta, Long::sum);
        long newItemBytes = itemBytes.length * Math.abs(delta);

        // Check if we're adding items and would exceed the byte limit
        if (delta > 0) {
            long estimated = cache.totalBytes + newItemBytes;
            if (estimated > getByteLimit(diskId)) {
                log("saveItem rejected for disk %s due to byte limit (%d > %d)", diskId, estimated, getByteLimit(diskId));
                return;
            }
        }

        // Precompute what the resulting count would be
        long base = cache.entries.getOrDefault(tapeKey, StoredEntry.EMPTY).count();
        long total = base + cache.deltaBuffer.getOrDefault(tapeKey, 0L);

        // If total count drops to zero or less, remove the key from both maps
        if (total <= 0) {
            cache.entries.remove(tapeKey);
            cache.deltaBuffer.remove(tapeKey);
        }

        // Write the operation to the WAL
        File wal = getWalFile(diskId);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(wal, true))) {
            dos.writeInt(itemBytes.length);
            dos.write(itemBytes);
            dos.writeLong(delta);
            dos.writeLong(checksum(itemBytes, delta));
            cache.totalBytes += itemBytes.length * Math.max(delta, 0);
        } catch (IOException e) {
            LOGGER.error("Failed to write WAL for disk {}: {}", diskId, e.getMessage());
        }

        // Auto-flush if buffer is large
        if (cache.deltaBuffer.size() >= FLUSH_THRESHOLD) {
            flush(diskId, cache);
        }
    }


    public static void flushAll() {
        for (var entry : activeCaches.entrySet()) {
            flush(entry.getKey(), entry.getValue());
        }
        log("flushAll complete.");
    }

    public static void flushAndEvict() {
        long now = System.currentTimeMillis();
        List<UUID> toEvict = new ArrayList<>();
        for (var entry : activeCaches.entrySet()) {
            UUID diskId = entry.getKey();
            TapeDriveCache cache = entry.getValue();
            flushAndSave(diskId, cache);
            if (!isPinned(diskId) && (now - cache.lastAccessed) > EVICTION_THRESHOLD) {
                toEvict.add(diskId);
            }
        }
        for (UUID id : toEvict) {
            activeCaches.remove(id);
            log("Evicted tape %s from RAM due to inactivity", id);
        }
    }

    private static void flushAndSave(UUID diskId, TapeDriveCache cache) {
        // Apply deltaBuffer to entries
        for (var entry : cache.deltaBuffer.entrySet()) {
            TapeKey key = entry.getKey();
            long delta = entry.getValue();
            StoredEntry current = cache.entries.getOrDefault(key, new StoredEntry(0, null));
            long updated = current.count() + delta;

            if (updated <= 0) {
                cache.entries.remove(key);
            } else {
                AEItemKey aeKey = current.aeKey();
                if (aeKey == null) {
                    ItemStack stack = deserializeItemStackFromBytes(key.itemBytes());
                    if (!stack.isEmpty()) {
                        aeKey = AEItemKey.of(stack);
                    }
                }
                cache.entries.put(key, new StoredEntry(updated, aeKey));
            }
        }

        // Clear the deltaBuffer
        cache.deltaBuffer.clear();

        // Recalculate totalBytes from flushed entries
        cache.totalBytes = cache.entries.entrySet().stream()
                .mapToLong(e -> e.getKey().itemBytes().length * e.getValue().count())
                .sum();

        // Write flushed entries to disk
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(getDiskFile(diskId)))) {
            for (var entry : cache.entries.entrySet()) {
                byte[] data = entry.getKey().itemBytes();
                dos.writeInt(data.length);
                dos.write(data);
                dos.writeLong(entry.getValue().count());
            }
        } catch (IOException e) {
            LOGGER.warn("Flush/save failed for disk {}: {}", diskId, e.getMessage());
        }

        // Clear WAL file
        try (FileOutputStream fos = new FileOutputStream(getWalFile(diskId))) {
            // truncate
        } catch (IOException e) {
            LOGGER.warn("Failed to clear WAL for disk {}: {}", diskId, e.getMessage());
        }
    }

    public static void shutdown() {
        try {
            flushAll();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            executor.shutdownNow();
        }
        activeCaches.clear();
    }

    private static TapeDriveCache loadFromDisk(UUID diskId) {
        File baseFile = getDiskFile(diskId);
        File walFile = getWalFile(diskId);
        TapeDriveCache cache = new TapeDriveCache();

        if (baseFile.exists()) {
            List<Map<String, Object>> backupEntries = new ArrayList<>();
            boolean hadInvalidItems = false;

            try (DataInputStream dis = new DataInputStream(new FileInputStream(baseFile))) {
                while (true) {
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long count = dis.readLong();

                    ItemStack stack = deserializeItemStackFromBytes(data);
                    if (!stack.isEmpty()) {
                        AEItemKey aeKey = AEItemKey.of(stack);
                        cache.entries.put(new TapeKey(data), new StoredEntry(count, aeKey));
                    } else {
                        hadInvalidItems = true;
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("count", count);
                        entry.put("rawBytes", Base64.getEncoder().encodeToString(data));
                        backupEntries.add(entry);
                    }
                }
            } catch (EOFException ignored) {
            } catch (IOException e) {
                LOGGER.warn("Failed reading DB for disk {}: {}", diskId, e.getMessage());
            }

            if (hadInvalidItems) {
                File out = getExportFolder().resolve(diskId + "_bak.json").toFile();
                try (PrintWriter writer = new PrintWriter(out)) {
                    writer.println("[");
                    for (int i = 0; i < backupEntries.size(); i++) {
                        Map<String, Object> entry = backupEntries.get(i);
                        writer.println("  {");
                        for (var it = entry.entrySet().iterator(); it.hasNext(); ) {
                            var e = it.next();
                            writer.print("    \"" + e.getKey() + "\": \"" + e.getValue() + "\"");
                            if (it.hasNext()) writer.println(",");
                            else writer.println();
                        }
                        writer.print(i == backupEntries.size() - 1 ? "  }\n" : "  },\n");
                    }
                    writer.println("]");
                    LOGGER.warn("§e[EnderDrives] Backup JSON created for tape {} due to unreadable entries ({} skipped).", diskId, backupEntries.size());
                } catch (IOException e) {
                    LOGGER.error("Failed to write backup JSON for tape {}: {}", diskId, e.getMessage());
                }
            }
        }

        if (walFile.exists()) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(walFile))) {
                while (true) {
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long delta = dis.readLong();
                    long checksum = dis.readLong();
                    if (checksum != checksum(data, delta)) continue;
                    TapeKey key = new TapeKey(data);
                    AEItemKey aeKey = AEItemKey.of(deserializeItemStackFromBytes(data));
                    long existing = cache.entries.getOrDefault(key, new StoredEntry(0, aeKey)).count();
                    long updated = existing + delta;
                    if (updated <= 0) {
                        cache.entries.remove(key);
                    } else {
                        cache.entries.put(key, new StoredEntry(updated, aeKey));
                    }
                }
            } catch (EOFException ignored) {} catch (IOException e) {
                LOGGER.warn("Failed WAL replay for disk {}: {}", diskId, e.getMessage());
            }
            walFile.delete();
        }

        cache.lastAccessed = System.currentTimeMillis();
        cache.totalBytes = cache.entries.entrySet().stream()
                .mapToLong(e -> e.getKey().itemBytes().length * e.getValue().count())
                .sum();
        return cache;
    }

    private static void flush(UUID diskId, TapeDriveCache cache) {
        if (cache.deltaBuffer.isEmpty()) return;
        for (var entry : cache.deltaBuffer.entrySet()) {
            TapeKey key = entry.getKey();
            long delta = entry.getValue();
            StoredEntry current = cache.entries.getOrDefault(key, new StoredEntry(0, null));
            long updated = current.count() + delta;
            if (updated <= 0) {
                cache.entries.remove(key);
            } else {
                AEItemKey aeKey = current.aeKey();
                if (aeKey == null) {
                    ItemStack stack = deserializeItemStackFromBytes(key.itemBytes());
                    if (!stack.isEmpty()) {
                        aeKey = AEItemKey.of(stack);
                    }
                }
                cache.entries.put(key, new StoredEntry(updated, aeKey));
            }
        }
        cache.deltaBuffer.clear();

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(getDiskFile(diskId)))) {
            for (var entry : cache.entries.entrySet()) {
                byte[] data = entry.getKey().itemBytes();
                dos.writeInt(data.length);
                dos.write(data);
                dos.writeLong(entry.getValue().count());
            }
        } catch (IOException e) {
            LOGGER.warn("Flush failed for disk {}: {}", diskId, e.getMessage());
        }

        try (FileOutputStream fos = new FileOutputStream(getWalFile(diskId))) {
            // Truncate
        } catch (IOException e) {
            LOGGER.warn("Failed to clear WAL for disk {}: {}", diskId, e.getMessage());
        }
    }

    private static long checksum(byte[] data, long delta) {
        CRC32 crc = new CRC32();
        crc.update(data);
        crc.update(longToBytes(delta));
        return crc.getValue();
    }

    private static byte[] longToBytes(long val) {
        return new byte[] {
                (byte) (val >>> 56), (byte) (val >>> 48),
                (byte) (val >>> 40), (byte) (val >>> 32),
                (byte) (val >>> 24), (byte) (val >>> 16),
                (byte) (val >>> 8), (byte) val
        };
    }

    public static File getDiskFile(UUID id) {
        return getFolder().resolve(id + ".bin").toFile();
    }

    private static File getWalFile(UUID id) {
        return getFolder().resolve(id + ".wal").toFile();
    }

    private static Path getFolder() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Path path = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("enderdrives")
                .resolve("TapeDrives");
        File folder = path.toFile();
        if (!folder.exists()) folder.mkdirs();
        return path;
    }

    private static Path getExportFolder() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Path path = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("enderdrives")
                .resolve("TapeDrives")
                .resolve("export");
        File folder = path.toFile();
        if (!folder.exists()) folder.mkdirs();
        return path;
    }

    public static boolean exportToJson(UUID tapeId) {
        File dbFile = getDiskFile(tapeId);
        if (!dbFile.exists()) return false;

        List<Map<String, Object>> entries = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new FileInputStream(dbFile))) {
            while (true) {
                int len = dis.readInt();
                byte[] data = new byte[len];
                dis.readFully(data);
                long count = dis.readLong();
                ItemStack stack = deserializeItemStackFromBytes(data);
                if (stack.isEmpty()) continue;
                Map<String, Object> jsonEntry = new LinkedHashMap<>();
                jsonEntry.put("count", count);
                jsonEntry.put("item", stack.getItem().toString());
                jsonEntry.put("displayName", stack.getDisplayName().getString());
                jsonEntry.put("nbt", stack.save(ServerLifecycleHooks.getCurrentServer().registryAccess()).toString());
                entries.add(jsonEntry);
            }
        } catch (EOFException ignored) {
        } catch (IOException e) {
            LOGGER.error("Failed to export tape {} to JSON: {}", tapeId, e.getMessage());
            return false;
        }

        File out = getExportFolder().resolve(tapeId + ".json").toFile();
        try (PrintWriter writer = new PrintWriter(out)) {
            writer.println("[");
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> entry = entries.get(i);
                writer.println("  {");
                for (var it = entry.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    writer.print("    \"" + e.getKey() + "\": \"" + e.getValue() + "\"");
                    if (it.hasNext()) writer.println(",");
                    else writer.println();
                }
                writer.print(i == entries.size() - 1 ? "  }\n" : "  },\n");
            }
            writer.println("]");
        } catch (IOException e) {
            LOGGER.error("Failed to write JSON export for tape {}", tapeId);
            return false;
        }

        return true;
    }

    public static boolean importFromJson(UUID tapeId) {
        File jsonFile = getExportFolder().resolve(tapeId + ".json").toFile();
        if (!jsonFile.exists()) return false;

        File dbFile = getDiskFile(tapeId);
        File walFile = getWalFile(tapeId);

        List<byte[]> serializedItems = new ArrayList<>();
        List<Long> counts = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(jsonFile))) {
            String line;
            StringBuilder currentNBT = new StringBuilder();
            long currentCount = 0;
            boolean inObject = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("{")) {
                    inObject = true;
                    currentNBT.setLength(0);
                    currentCount = 0;
                } else if (line.startsWith("\"count\"")) {
                    String raw = line.split(":")[1].replaceAll("[\",]", "").trim();
                    currentCount = Long.parseLong(raw);
                } else if (line.startsWith("\"nbt\"")) {
                    String nbtRaw = line.substring(line.indexOf(":") + 1).trim();
                    if (nbtRaw.endsWith(",")) nbtRaw = nbtRaw.substring(0, nbtRaw.length() - 1);
                    String nbtContent = nbtRaw.replaceFirst("^\"", "").replaceFirst("\"$", "");
                    currentNBT.append(nbtContent);
                } else if (line.startsWith("}")) {
                    inObject = false;
                    try {
                        net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(currentNBT.toString());
                        var provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
                        ItemStack stack = ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
                        if (!stack.isEmpty()) {
                            byte[] data = com.sts15.enderdrives.items.TapeDiskItem.serializeItemStackToBytes(stack);
                            serializedItems.add(data);
                            counts.add(currentCount);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to import item for tape {}: {}", tapeId, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed reading export JSON for tape {}: {}", tapeId, e.getMessage());
            return false;
        }

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(dbFile))) {
            for (int i = 0; i < serializedItems.size(); i++) {
                byte[] data = serializedItems.get(i);
                long count = counts.get(i);
                dos.writeInt(data.length);
                dos.write(data);
                dos.writeLong(count);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write new binary DB for tape {}: {}", tapeId, e.getMessage());
            return false;
        }

        if (walFile.exists()) {
            walFile.delete();
        }

        releaseFromRAM(tapeId);

        LOGGER.info("Successfully imported {} items into tape {} and released from RAM", serializedItems.size(), tapeId);
        return true;

    }

    public static List<File> getSortedBinFilesOldestFirst() {
        File folder = getFolder().toFile();
        File[] binFiles = folder.listFiles((dir, name) -> name.endsWith(".bin"));
        if (binFiles == null) return List.of();

        List<File> list = Arrays.asList(binFiles);
        list.sort(Comparator.comparingLong(File::lastModified));
        return list;
    }

    public static boolean deleteTape(UUID tapeId) {
        if (activeCaches.containsKey(tapeId)) {
            return false;
        }

        File bin = getDiskFile(tapeId);
        File wal = getWalFile(tapeId);
        boolean deleted = false;

        if (bin.exists()) deleted |= bin.delete();
        if (wal.exists()) deleted |= wal.delete();

        return deleted;
    }

    public static boolean isPinned(UUID id) {
        return pinnedTapes.contains(id);
    }

    public static void pin(UUID id) {
        pinnedTapes.add(id);
    }

    public static void unpin(UUID id) {
        pinnedTapes.remove(id);
    }

    public static Set<UUID> getPinnedTapes() {
        return Collections.unmodifiableSet(pinnedTapes);
    }

    public static class TapeDriveCache {
        public final ConcurrentHashMap<TapeKey, StoredEntry> entries = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<TapeKey, Long> deltaBuffer = new ConcurrentHashMap<>();
        public volatile long lastAccessed = System.currentTimeMillis();
        public long totalBytes = 0;
    }

    private static void log(String format, Object... args) {
        if (debug_log) LOGGER.info(String.format(format, args));
    }
}