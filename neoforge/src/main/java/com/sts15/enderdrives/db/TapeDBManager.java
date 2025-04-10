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
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static final int FLUSH_THRESHOLD = serverConfig.TAPE_DB_FLUSH_THRESHOLD.get();
    private static final long FLUSH_INTERVAL = serverConfig.TAPE_DB_FLUSH_INTERVAL.get();
    private static final long EVICTION_THRESHOLD = serverConfig.TAPE_DB_RAM_EVICT_TIMEOUT.get();
    private static final int PAGE_SIZE = 10000;
    private static final double BYTE_COST_MULTIPLIER = 0.75;
    static boolean debug_log = serverConfig.TAPE_DB_DEBUG_LOG.get();

    public static void init() {
        long start = System.currentTimeMillis();
        Runtime.getRuntime().addShutdownHook(new Thread(TapeDBManager::flushAll));
        executor.scheduleAtFixedRate(TapeDBManager::flushAndEvict, FLUSH_INTERVAL, FLUSH_INTERVAL, TimeUnit.MILLISECONDS);
        log("TapeDBManager initialized in {} ms", System.currentTimeMillis() - start);
    }

    public static void saveItem(UUID diskId, byte[] itemBytes, AEItemKey key, long delta) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        TapeKey tapeKey = new TapeKey(itemBytes);
        cache.lastAccessed = System.currentTimeMillis();
        cache.deltaBuffer.merge(tapeKey, delta, Long::sum);
        long newItemBytes = itemBytes.length * Math.abs(delta);
        if (delta > 0) {
            long estimated = cache.totalBytes + newItemBytes;
            if (estimated > getByteLimit(diskId)) {
                log("saveItem rejected for disk %s due to byte limit (%d > %d)", diskId, estimated, getByteLimit(diskId));
                return;
            }
        }
        File wal = getWalFile(diskId);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(wal, true))) {
            dos.writeInt(itemBytes.length);
            dos.write(itemBytes);
            dos.writeLong(delta);
            dos.writeLong(checksum(itemBytes, delta));
            cache.totalBytes += itemBytes.length * delta;
        } catch (IOException e) {
            LOGGER.error("Failed to write WAL for disk {}: {}", diskId, e.getMessage());
        }

        if (cache.deltaBuffer.size() >= FLUSH_THRESHOLD) {
            flush(diskId, cache);
        }
        log("saveItem for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
    }

    public static long getItemCount(UUID diskId, byte[] itemBytes) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        TapeKey key = new TapeKey(itemBytes);
        long committed = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
        long delta = cache.deltaBuffer.getOrDefault(key, 0L);
        long result = Math.max(0, committed + delta);
        log("getItemCount for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return result;
    }

    public static boolean isKnownItem(UUID diskId, byte[] itemBytes) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        TapeKey key = new TapeKey(itemBytes);
        boolean known = cache.entries.containsKey(key) || cache.deltaBuffer.containsKey(key);
        log("isKnownItem for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return known;
    }

    public static int getTypeCount(UUID diskId) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        int count = cache.entries.size();
        log("getTypeCount for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return count;
    }

    public static List<TapeKeyCacheEntry> readAllItems(UUID diskId, int page) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        int skip = page * PAGE_SIZE;
        List<TapeKeyCacheEntry> list = cache.entries.entrySet().stream()
                .skip(skip)
                .limit(PAGE_SIZE)
                .map(entry -> new TapeKeyCacheEntry(
                        entry.getKey().itemBytes(),
                        entry.getValue().aeKey(),
                        entry.getValue().count()))
                .toList();
        log("readAllItems (page {}) for disk {} completed in {} ms", page, diskId, System.currentTimeMillis() - start);
        return list;
    }

    public static List<TapeKeyCacheEntry> readAllItems(UUID diskId) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        List<TapeKeyCacheEntry> list = cache.entries.entrySet().stream()
                .map(entry -> new TapeKeyCacheEntry(
                        entry.getKey().itemBytes(),
                        entry.getValue().aeKey(),
                        entry.getValue().count()))
                .toList();
        log("readAllItems for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return list;
    }

    public static long getTotalStoredBytes(UUID diskId) {
        TapeDriveCache cache = getCache(diskId);
        if (cache == null) return 0L;

        long totalBytes = 0L;

        for (Map.Entry<TapeKey, StoredEntry> entry : cache.entries.entrySet()) {
            long count = entry.getValue().count();
            totalBytes += Math.round(entry.getKey().itemBytes().length * count * BYTE_COST_MULTIPLIER);
        }

        for (Map.Entry<TapeKey, Long> delta : cache.deltaBuffer.entrySet()) {
            long count = delta.getValue();
            totalBytes += Math.round(delta.getKey().itemBytes().length * count * BYTE_COST_MULTIPLIER);
        }

        return Math.max(0, totalBytes);
    }


    public static long getByteLimit(UUID tapeId) {
        return serverConfig.TAPE_DISK_BYTE_LIMIT.get();
    }

    public static void flushAll() {
        long start = System.currentTimeMillis();
        for (var entry : activeCaches.entrySet()) {
            flush(entry.getKey(), entry.getValue());
        }
        log("flushAll completed in {} ms", System.currentTimeMillis() - start);
    }

    public static void flushAndEvict() {
        long start = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        List<UUID> toEvict = new ArrayList<>();
        for (var entry : activeCaches.entrySet()) {
            UUID diskId = entry.getKey();
            TapeDriveCache cache = entry.getValue();
            flush(diskId, cache);
            if ((now - cache.lastAccessed) >= EVICTION_THRESHOLD) {
                toEvict.add(diskId);
            }
        }
        for (UUID diskId : toEvict) {
            activeCaches.remove(diskId);
            log("Evicted tape drive {} from RAM due to inactivity", diskId);
        }
        log("flushAndEvict completed in {} ms", System.currentTimeMillis() - start);
    }

    public static void unload(UUID diskId) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = activeCaches.remove(diskId);
        if (cache != null) {
            flush(diskId, cache);
        }
        log("unload for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
    }

    public static Collection<StoredEntry> getAllEntries(UUID diskId) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = getOrLoad(diskId);
        cache.lastAccessed = System.currentTimeMillis();
        Collection<StoredEntry> result = cache.entries.values();
        log("getAllEntries for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return result;
    }

    private static TapeDriveCache getOrLoad(UUID diskId) {
        long start = System.currentTimeMillis();
        TapeDriveCache cache = activeCaches.computeIfAbsent(diskId, TapeDBManager::loadFromDisk);
        log("getOrLoad for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return cache;
    }

    public static CompletableFuture<TapeDriveCache> loadFromDiskAsync(UUID diskId) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            TapeDriveCache cache = loadFromDisk(diskId);
            activeCaches.put(diskId, cache);
            log("loadFromDiskAsync for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
            return cache;
        }, executor);
    }

    private static TapeDriveCache loadFromDisk(UUID diskId) {
        long start = System.currentTimeMillis();
        File baseFile = getDiskFile(diskId);
        File walFile = getWalFile(diskId);
        TapeDriveCache cache = new TapeDriveCache();

        if (baseFile.exists()) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(baseFile))) {
                while (true) {
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long count = dis.readLong();
                    AEItemKey aeKey = AEItemKey.of(deserializeItemStackFromBytes(data));
                    cache.entries.put(new TapeKey(data), new StoredEntry(count, aeKey));
                }
            } catch (EOFException ignored) {
                // Reached end of file.
            } catch (IOException e) {
                LOGGER.warn("Failed reading DB for disk {}: {}", diskId, e.getMessage());
            }
        }

        if (walFile.exists()) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(walFile))) {
                while (true) {
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long delta = dis.readLong();
                    long walChecksum = dis.readLong();
                    if (walChecksum != checksum(data, delta)) {
                        LOGGER.warn("Checksum mismatch in WAL for disk {}", diskId);
                        continue;
                    }
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
            } catch (EOFException ignored) {
            } catch (IOException e) {
                LOGGER.warn("Failed to replay WAL for disk {}: {}", diskId, e.getMessage());
            }
            if (!walFile.delete()) {
                LOGGER.warn("Failed to delete WAL file for disk {}", diskId);
            }
        }
        cache.lastAccessed = System.currentTimeMillis();
        cache.totalBytes = cache.entries.entrySet().stream()
                .mapToLong(e -> e.getKey().itemBytes().length * e.getValue().count())
                .sum();
        log("loadFromDisk for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
        return cache;
    }

    private static void flush(UUID diskId, TapeDriveCache cache) {
        long start = System.currentTimeMillis();
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
        File db = getDiskFile(diskId);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(db))) {
            for (var entry : cache.entries.entrySet()) {
                byte[] data = entry.getKey().itemBytes();
                dos.writeInt(data.length);
                dos.write(data);
                dos.writeLong(entry.getValue().count());
            }
        } catch (IOException e) {
            log("DB snapshot failed for disk {}: {}", diskId, e.getMessage());
        }
        try (FileOutputStream fos = new FileOutputStream(getWalFile(diskId))) {
            // File truncated by opening in write mode.
        } catch (IOException e) {
            log("Failed to truncate WAL for disk {}: {}", diskId, e.getMessage());
        }
        log("flush for disk {} completed in {} ms", diskId, System.currentTimeMillis() - start);
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
                (byte) (val >>> 8), (byte) (val)
        };
    }

    private static File getDiskFile(UUID id) {
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
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return path;
    }

    public static TapeDriveCache getCache(UUID diskId) {
        return activeCaches.get(diskId);
    }

    public static class TapeDriveCache {
        public final ConcurrentHashMap<TapeKey, StoredEntry> entries = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<TapeKey, Long> deltaBuffer = new ConcurrentHashMap<>();
        volatile long lastAccessed = System.currentTimeMillis();
        public long totalBytes = 0;
    }

    private static void log(String format, Object... args) {
        if (debug_log) {
            LOGGER.info(String.format(format, args));
        }
    }

}