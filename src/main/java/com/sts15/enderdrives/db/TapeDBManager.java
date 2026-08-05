package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.util.StackCodecHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static com.sts15.enderdrives.items.TapeDiskItem.deserializeItemStackFromBytes;

public class TapeDBManager {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives-TapeDB");
    private static final Map<UUID, TapeDriveCache> activeCaches = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<TapeDriveCache>> pendingLoads = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> tapeLocks = new ConcurrentHashMap<>();
    private static final Set<UUID> pinnedTapes = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService executor = createExecutor();
    private static volatile boolean initialized;
    private static volatile MinecraftServer activeServer;
    private static volatile long lifecycleGeneration;
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();
    private static volatile int flushThreshold = 500;
    private static volatile long flushInterval = 5_000L;
    private static volatile long evictionThreshold = 300_000L;
    private static final int MAX_SERIALIZED_ITEM_BYTES = 64 * 1024 * 1024;
    private static final long MAX_DATABASE_FILE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_WAL_FILE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_IMPORT_FILE_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_IMPORT_NBT_CHARS = 128 * 1024 * 1024;
    private static final String CHECKPOINT_MAGIC = "ENDERDRIVES_TAPE_CHECKPOINT_1";
    private static volatile boolean debugLog;

    public static synchronized void init() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.error("Cannot initialize TapeDBManager without an active server");
            return;
        }
        if (initialized && activeServer == server
                && !executor.isShutdown() && !executor.isTerminated()) return;
        if (initialized) shutdown();
        if (executor.isShutdown() || executor.isTerminated()) {
            executor = createExecutor();
        }
        flushThreshold = serverConfig.TAPE_DB_FLUSH_THRESHOLD.get();
        flushInterval = serverConfig.TAPE_DB_FLUSH_INTERVAL.get();
        evictionThreshold = serverConfig.TAPE_DB_RAM_EVICT_TIMEOUT.get();
        debugLog = serverConfig.TAPE_DB_DEBUG_LOG.get();
        activeServer = server;
        lifecycleGeneration++;
        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(TapeDBManager::shutdown, "EnderDrives-TapeDB-Shutdown"));
        }
        initialized = true;
        executor.scheduleAtFixedRate(
                TapeDBManager::runMaintenanceSafely,
                flushInterval,
                flushInterval,
                TimeUnit.MILLISECONDS);
        log("TapeDBManager initialized.");
    }

    private static void runMaintenanceSafely() {
        try {
            flushAndEvict();
        } catch (Throwable t) {
            LOGGER.error("TapeDB maintenance task failed", t);
        }
    }

    public static TapeDriveCache getCache(UUID diskId) {
        TapeDriveCache cache = activeCaches.get(diskId);
        return cache != null && cache.generation == lifecycleGeneration ? cache : null;
    }

    public static TapeDriveCache getCacheSafe(UUID diskId) {
        return getCache(diskId);
    }

    public static TapeDriveCache getOrLoadForRead(UUID diskId) {
        Objects.requireNonNull(diskId, "diskId");
        long generation = lifecycleGeneration;
        if (!isGenerationActive(generation)) return null;
        synchronized (getTapeLock(diskId)) {
            if (!isGenerationActive(generation)) return null;
            try {
                return getOrLoadCurrentLocked(diskId, generation);
            } catch (RuntimeException error) {
                LOGGER.error("Failed to load tape {}", diskId, error);
                return null;
            }
        }
    }

    private static TapeDriveCache getOrLoadCurrentLocked(UUID diskId, long generation) {
        if (!isGenerationActive(generation)) {
            return null;
        }

        TapeDriveCache cached = activeCaches.get(diskId);
        if (cached != null) {
            return cached.generation == generation ? cached : null;
        }

        TapeDriveCache loaded = loadFromDisk(diskId, generation);

        if (!isGenerationActive(generation)) {
            return null;
        }

        TapeDriveCache existing = activeCaches.putIfAbsent(diskId, loaded);
        return existing != null ? existing : loaded;
    }

    private static Object getTapeLock(UUID diskId) {
        return tapeLocks.computeIfAbsent(diskId, id -> new Object());
    }

    private static boolean isGenerationActive(long generation) {
        return initialized
                && activeServer != null
                && generation == lifecycleGeneration;
    }

    public static CompletableFuture<TapeDriveCache> loadFromDiskAsync(UUID diskId) {
        Objects.requireNonNull(diskId, "diskId");
        long generation = lifecycleGeneration;
        if (!isGenerationActive(generation) || executor.isShutdown() || executor.isTerminated()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Tape database is not running"));
        }
        TapeDriveCache cached = getCache(diskId);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        CompletableFuture<TapeDriveCache> promise = new CompletableFuture<>();
        CompletableFuture<TapeDriveCache> existing = pendingLoads.putIfAbsent(diskId, promise);
        if (existing != null) return existing;

        try {
            executor.execute(() -> {
                try {
                    TapeDriveCache cache;
                    synchronized (getTapeLock(diskId)) {
                        if (!isGenerationActive(generation)) {
                            throw new CancellationException("Tape database lifecycle changed");
                        }
                        cache = getOrLoadCurrentLocked(diskId, generation);
                    }
                    if (cache == null || !isGenerationActive(generation)) {
                        throw new CancellationException("Tape database lifecycle changed");
                    }
                    promise.complete(cache);
                } catch (Throwable error) {
                    promise.completeExceptionally(error);
                } finally {
                    pendingLoads.remove(diskId, promise);
                }
            });
        } catch (RejectedExecutionException error) {
            pendingLoads.remove(diskId, promise);
            promise.completeExceptionally(error);
        }
        return promise;
    }

    public static long getItemCount(UUID diskId, byte[] itemBytes) {
        if (itemBytes == null || itemBytes.length == 0) return 0L;
        synchronized (getTapeLock(diskId)) {
            TapeDriveCache cache = getOrLoadForRead(diskId);
            if (cache == null) return 0L;
            synchronized (cache.lock) {
                cache.lastAccessed = System.currentTimeMillis();
                TapeKey key = new TapeKey(itemBytes);
                long committed = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
                long delta = cache.deltaBuffer.getOrDefault(key, 0L);
                return saturatedCount(committed, delta);
            }
        }
    }

    public static int getTypeCount(UUID diskId) {
        synchronized (getTapeLock(diskId)) {
            TapeDriveCache cache = getOrLoadForRead(diskId);
            if (cache == null) return 0;
            synchronized (cache.lock) {
                cache.lastAccessed = System.currentTimeMillis();
                return getTypeCountLocked(cache);
            }
        }
    }

    public static long getTotalStoredBytes(UUID diskId) {
        synchronized (getTapeLock(diskId)) {
            TapeDriveCache cache = getOrLoadForRead(diskId);
            if (cache == null) return 0L;
            synchronized (cache.lock) {
                cache.lastAccessed = System.currentTimeMillis();
                cache.totalBytes = calculateTotalBytes(cache.entries, cache.deltaBuffer);
                return cache.totalBytes;
            }
        }
    }

    public static long getByteLimit(UUID diskId) {
        return serverConfig.TAPE_DISK_BYTE_LIMIT.get();
    }

    public static long getMaxInsertable(UUID diskId, byte[] itemBytes, long requested) {
        if (requested <= 0 || itemBytes == null
                || itemBytes.length == 0 || itemBytes.length > MAX_SERIALIZED_ITEM_BYTES) return 0;

        synchronized (getTapeLock(diskId)) {
            TapeDriveCache cache = getOrLoadForRead(diskId);
            if (cache == null) return 0;
            synchronized (cache.lock) {
                if (getCache(diskId) != cache || cache.blocked) return 0;
                cache.lastAccessed = System.currentTimeMillis();
                cache.totalBytes = calculateTotalBytes(cache.entries, cache.deltaBuffer);

                TapeKey tapeKey = new TapeKey(itemBytes);
                long base = cache.entries.getOrDefault(tapeKey, StoredEntry.EMPTY).count();
                long pending = cache.deltaBuffer.getOrDefault(tapeKey, 0L);
                long current = saturatedCount(base, pending);
                return maxInsertableLocked(diskId, cache, itemBytes, current, requested);
            }
        }
    }

    private static void blockCache(
            UUID diskId,
            TapeDriveCache cache,
            String message,
            Throwable error
    ) {
        cache.blocked = true;

        if (error == null) {
            LOGGER.error("Tape {} blocked: {}", diskId, message);
        } else {
            LOGGER.error("Tape {} blocked: {}", diskId, message, error);
        }
    }

    private static boolean ensureWalCapacityLocked(
            UUID diskId,
            TapeDriveCache cache,
            int itemBytesLength
    ) {
        long recordLength = Integer.BYTES
                + (long) itemBytesLength
                + Long.BYTES
                + Long.BYTES;

        File walFile = getWalFile(diskId);
        long currentLength = walFile.exists() ? walFile.length() : 0L;

        if (recordLength > MAX_WAL_FILE_BYTES) {
            blockCache(
                    diskId,
                    cache,
                    "A single WAL record exceeds the maximum WAL file size",
                    null
            );
            return false;
        }

        if (currentLength <= MAX_WAL_FILE_BYTES - recordLength) {
            return true;
        }

        // Checkpoint current changes and truncate the WAL before appending.
        if (!persistCacheLocked(diskId, cache)) {
            LOGGER.error(
                    "Could not checkpoint tape {} before its WAL reached the size limit",
                    diskId
            );
            return false;
        }

        currentLength = walFile.exists() ? walFile.length() : 0L;
        if (currentLength > MAX_WAL_FILE_BYTES - recordLength) {
            blockCache(
                    diskId,
                    cache,
                    "Tape WAL remains too large after checkpointing",
                    null
            );
            return false;
        }

        return true;
    }

    public static void releaseFromRAM(UUID id) {
        synchronized (getTapeLock(id)) {
            TapeDriveCache cache = getCache(id);
            if (cache == null) return;

            synchronized (cache.lock) {
                if (getCache(id) != cache) return;
                if (persistCacheLocked(id, cache)) {
                    activeCaches.remove(id, cache);
                    log("Released and saved tape {} from RAM", id);
                }
            }
        }
    }

    public static Set<UUID> getActiveTapeIds() {
        long generation = lifecycleGeneration;
        Set<UUID> result = new HashSet<>();
        activeCaches.forEach((id, cache) -> {
            if (cache.generation == generation) result.add(id);
        });
        return result;
    }

    public static long saveItem(UUID diskId, byte[] itemBytes, AEItemKey key, long delta) {
        return mutateItem(diskId, itemBytes, key, delta, null);
    }

    public static long insertItem(
            UUID diskId,
            byte[] itemBytes,
            AEItemKey key,
            long requested,
            int typeLimit
    ) {
        return mutateItem(diskId, itemBytes, key, requested, Math.max(0, typeLimit));
    }

    private static long mutateItem(
            UUID diskId,
            byte[] itemBytes,
            AEItemKey key,
            long delta,
            @org.jetbrains.annotations.Nullable Integer typeLimit
    ) {
        if (delta == 0
                || itemBytes == null || itemBytes.length == 0
                || itemBytes.length > MAX_SERIALIZED_ITEM_BYTES) return 0;

        long generation = lifecycleGeneration;
        if (!isGenerationActive(generation)) return 0;
        byte[] stableBytes = Arrays.copyOf(itemBytes, itemBytes.length);
        if (delta > 0) {
            ItemStack decoded = deserializeItemStackFromBytes(stableBytes);
            if (decoded.isEmpty() || key == null || !key.equals(AEItemKey.of(decoded))) return 0;
        }

        synchronized (getTapeLock(diskId)) {
            if (!isGenerationActive(generation)) return 0;
            TapeDriveCache cache = getOrLoadCurrentLocked(diskId, generation);
            if (cache == null) return 0;

            synchronized (cache.lock) {
                if (getCache(diskId) != cache || !isGenerationActive(generation) || cache.blocked) return 0;
                if (!ensureWalCapacityLocked(diskId, cache, stableBytes.length)) return 0;

                TapeKey tapeKey = new TapeKey(stableBytes);
                cache.lastAccessed = System.currentTimeMillis();
                cache.totalBytes = calculateTotalBytes(cache.entries, cache.deltaBuffer);
                long base = cache.entries.getOrDefault(tapeKey, StoredEntry.EMPTY).count();
                long pending = cache.deltaBuffer.getOrDefault(tapeKey, 0L);
                long current;
                try {
                    current = Math.addExact(base, pending);
                } catch (ArithmeticException error) {
                    blockCache(diskId, cache, "Count overflow in the in-memory tape state", error);
                    return 0;
                }
                if (current < 0) {
                    blockCache(diskId, cache, "Negative count in the in-memory tape state", null);
                    return 0;
                }
                if (delta > 0 && typeLimit != null && current == 0
                        && getTypeCountLocked(cache) >= typeLimit) return 0;
                long requestedRemoval = delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta;
                long effectiveDelta = delta < 0
                        ? -Math.min(current, requestedRemoval)
                        : maxInsertableLocked(diskId, cache, stableBytes, current, delta);
                if (effectiveDelta == 0) return 0;
                long updatedCount;
                long updatedPending;
                try {
                    updatedCount = Math.addExact(current, effectiveDelta);
                    updatedPending = Math.addExact(pending, effectiveDelta);
                } catch (ArithmeticException error) {
                    blockCache(diskId, cache, "Count overflow while applying a tape mutation", error);
                    return 0;
                }
                if (updatedCount < 0) {
                    blockCache(diskId, cache, "Tape mutation would create a negative count", null);
                    return 0;
                }

                // The WAL must contain the operation before it becomes visible in RAM.
                if (!appendWalRecord(diskId, stableBytes, effectiveDelta)) return 0;

                if (updatedPending == 0L) {
                    cache.deltaBuffer.remove(tapeKey);
                } else {
                    cache.deltaBuffer.put(tapeKey, updatedPending);
                }
                long bytesWithoutCurrent = Math.max(0L, cache.totalBytes - byteCost(stableBytes, current));
                cache.totalBytes = saturatedAdd(bytesWithoutCurrent, byteCost(stableBytes, updatedCount));
                cache.dirty = true;

                if (cache.deltaBuffer.size() >= flushThreshold) {
                    persistCacheLocked(diskId, cache);
                }
                return effectiveDelta;
            }
        }
    }

    private static boolean appendWalRecord(
            UUID diskId,
            byte[] itemBytes,
            long delta
    ) {
        if (itemBytes == null
                || itemBytes.length == 0
                || itemBytes.length > MAX_SERIALIZED_ITEM_BYTES
                || delta == 0) {
            return false;
        }

        File walFile = getWalFile(diskId);

        try (FileOutputStream fos = new FileOutputStream(walFile, true);
             DataOutputStream output =
                     new DataOutputStream(new BufferedOutputStream(fos))) {

            output.writeInt(itemBytes.length);
            output.write(itemBytes);
            output.writeLong(delta);
            output.writeLong(checksum(itemBytes, delta));

            output.flush();
            fos.getFD().sync();
            return true;

        } catch (IOException error) {
            LOGGER.error(
                    "Failed to append WAL record for tape {}",
                    diskId,
                    error
            );
            return false;
        }
    }

    public static void flushAll() {
        flushAllInternal();
        log("flushAll complete.");
    }

    private static boolean flushAllInternal() {
        boolean success = true;

        for (var entry : activeCaches.entrySet()) {
            UUID diskId = entry.getKey();
            TapeDriveCache cache = entry.getValue();

            synchronized (getTapeLock(diskId)) {
                synchronized (cache.lock) {
                    if (getCache(diskId) != cache) {
                        continue;
                    }

                    if (!persistCacheLocked(diskId, cache)) {
                        success = false;
                    }
                }
            }
        }

        return success;
    }

    public static void flushAndEvict() {
        if (!initialized || activeServer == null) return;
        long now = System.currentTimeMillis();
        for (var entry : activeCaches.entrySet()) {
            UUID diskId = entry.getKey();
            TapeDriveCache cache = entry.getValue();
            synchronized (getTapeLock(diskId)) {
                synchronized (cache.lock) {
                    if (getCache(diskId) != cache) continue;
                    boolean persisted = persistCacheLocked(diskId, cache);
                    boolean expired = now >= cache.lastAccessed
                            && now - cache.lastAccessed > evictionThreshold;
                    if (persisted && !isPinned(diskId) && expired) {
                        activeCaches.remove(diskId, cache);
                        log("Evicted tape {} from RAM due to inactivity", diskId);
                    }
                }
            }
        }
    }

    private static boolean flushAndSave(UUID diskId, TapeDriveCache cache) {
        synchronized (getTapeLock(diskId)) {
            synchronized (cache.lock) {
                return getCache(diskId) != cache || persistCacheLocked(diskId, cache);
            }
        }
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;
        CancellationException cancellation = new CancellationException("Tape database is shutting down");
        pendingLoads.values().forEach(future -> future.completeExceptionally(cancellation));
        try {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOGGER.error("Tape database executor did not terminate cleanly");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (!flushAllInternal()) {
            LOGGER.error("One or more tape snapshots could not be checkpointed during shutdown; their WAL files were preserved");
        }
        activeCaches.clear();
        pendingLoads.clear();
        pinnedTapes.clear();
        activeServer = null;
        lifecycleGeneration++;
    }

    private static ItemStack decodeItemSafely(byte[] data) {
        if (data == null || data.length == 0) {
            return ItemStack.EMPTY;
        }

        try {
            ItemStack stack = deserializeItemStackFromBytes(data);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static TapeDriveCache loadFromDisk(UUID diskId, long generation) {
        File baseFile = getDiskFile(diskId);
        File walFile = getWalFile(diskId);
        TapeDriveCache cache = new TapeDriveCache(generation);
        boolean replayedWal = false;
        RecoveryDecision recovery = recoverCheckpoint(diskId);
        cache.blocked = recovery == RecoveryDecision.BLOCKED;

        if (baseFile.exists()) {
            List<UnreadableRecord> backupEntries = new ArrayList<>();
            long fileSize = baseFile.length();

            if (fileSize > MAX_DATABASE_FILE_BYTES) {
                cache.blocked = true;
                LOGGER.error("Tape {} base file is too large ({} bytes); storage is blocked", diskId, fileSize);
            } else try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(baseFile)))) {
                long remaining = fileSize;
                while (remaining > 0) {
                    if (remaining < Integer.BYTES) {
                        throw new IOException("Incomplete tape record header");
                    }
                    int len = dis.readInt();
                    remaining -= Integer.BYTES;
                    if (len <= 0 || len > MAX_SERIALIZED_ITEM_BYTES) {
                        throw new IOException("Invalid tape record length: " + len);
                    }
                    long payloadLength = (long) len + Long.BYTES;
                    if (remaining < payloadLength) {
                        throw new IOException("Incomplete tape record payload");
                    }
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long count = dis.readLong();
                    remaining -= payloadLength;
                    if (count <= 0) {
                        throw new IOException("Invalid non-positive tape count: " + count);
                    }

                    ItemStack stack = decodeItemSafely(data);
                    AEItemKey aeKey = stack.isEmpty() ? null : AEItemKey.of(stack);
                    TapeKey tapeKey = new TapeKey(data);
                    StoredEntry previous = cache.entries.getOrDefault(tapeKey, StoredEntry.EMPTY);
                    long merged;
                    try {
                        merged = Math.addExact(previous.count(), count);
                    } catch (ArithmeticException overflow) {
                        throw new IOException("Tape count overflow for duplicate base records", overflow);
                    }
                    cache.entries.put(tapeKey, new StoredEntry(
                            merged, previous.aeKey() != null ? previous.aeKey() : aeKey));
                    if (aeKey == null) backupEntries.add(new UnreadableRecord(count, data));
                }
            } catch (IOException e) {
                cache.blocked = true;
                LOGGER.warn("Failed reading DB for disk {}: {}", diskId, e.getMessage());
            }

            if (!backupEntries.isEmpty()) {
                File out = getExportFolder().resolve(diskId + "_bak.json").toFile();
                try (PrintWriter writer = new PrintWriter(out)) {
                    writer.println("[");
                    for (int i = 0; i < backupEntries.size(); i++) {
                        UnreadableRecord entry = backupEntries.get(i);
                        writer.println("  {");
                        writer.println("    \"count\": \"" + entry.count() + "\",");
                        writer.println("    \"rawBytes\": \""
                                + Base64.getEncoder().encodeToString(entry.itemBytes()) + "\"");
                        writer.print(i == backupEntries.size() - 1 ? "  }\n" : "  },\n");
                    }
                    writer.println("]");
                    LOGGER.warn("§e[EnderDrives] Backup JSON created for tape {} due to unreadable entries ({} skipped).", diskId, backupEntries.size());
                } catch (IOException e) {
                    LOGGER.error("Failed to write backup JSON for tape {}: {}", diskId, e.getMessage());
                }
            }
        }

        if (walFile.exists() && recovery == RecoveryDecision.REPLAY_WAL) {
            long fileSize = walFile.length();
            if (fileSize > MAX_WAL_FILE_BYTES) {
                cache.blocked = true;
                LOGGER.error("Tape {} WAL is too large ({} bytes); preserving it and blocking storage",
                        diskId, fileSize);
            } else try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(walFile)))) {
                long remaining = fileSize;
                while (remaining > 0) {
                    if (remaining < Integer.BYTES) {
                        throw new IOException("Incomplete WAL record header");
                    }
                    int len = dis.readInt();
                    remaining -= Integer.BYTES;
                    if (len <= 0 || len > MAX_SERIALIZED_ITEM_BYTES) {
                        throw new IOException("Invalid WAL record length: " + len);
                    }
                    long payloadLength = (long) len + Long.BYTES + Long.BYTES;
                    if (remaining < payloadLength) {
                        throw new IOException("Incomplete WAL record payload");
                    }
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long delta = dis.readLong();
                    long checksum = dis.readLong();
                    remaining -= payloadLength;
                    if (delta == 0) throw new IOException("WAL contains a zero delta");
                    if (checksum != checksum(data, delta)) {
                        throw new IOException("WAL checksum mismatch");
                    }
                    TapeKey key = new TapeKey(data);
                    ItemStack stack = decodeItemSafely(data);
                    AEItemKey aeKey = stack.isEmpty() ? null : AEItemKey.of(stack);
                    StoredEntry previous = cache.entries.getOrDefault(key, StoredEntry.EMPTY);
                    long updated;
                    try {
                        updated = Math.addExact(previous.count(), delta);
                    } catch (ArithmeticException overflow) {
                        throw new IOException("WAL count overflow", overflow);
                    }
                    if (updated < 0) throw new IOException("WAL would create a negative count");
                    if (updated == 0) {
                        cache.entries.remove(key);
                    } else {
                        cache.entries.put(key, new StoredEntry(
                                updated, previous.aeKey() != null ? previous.aeKey() : aeKey));
                    }
                    replayedWal = true;
                }
            } catch (IOException e) {
                cache.blocked = true;
                LOGGER.error("Failed WAL replay for disk {}; preserving its WAL and blocking storage: {}",
                        diskId, e.getMessage());
            }
        }

        cache.lastAccessed = System.currentTimeMillis();
        cache.totalBytes = calculateTotalBytes(cache.entries, Map.of());
        cache.dirty = replayedWal;

        // A replayed WAL remains authoritative until its merged base snapshot
        // has been installed. Never delete recovery data merely because it was read.
        if (replayedWal && !cache.blocked) persistCacheLocked(diskId, cache);
        return cache;
    }

    private static boolean persistCacheLocked(UUID diskId, TapeDriveCache cache) {
        if (cache.blocked) return false;
        if (!cache.dirty && cache.deltaBuffer.isEmpty()) return true;

        Map<TapeKey, StoredEntry> snapshot = new HashMap<>(cache.entries);
        for (var entry : cache.deltaBuffer.entrySet()) {
            TapeKey tapeKey = entry.getKey();
            StoredEntry current = snapshot.getOrDefault(tapeKey, StoredEntry.EMPTY);
            long updated;
            try {
                updated = Math.addExact(current.count(), entry.getValue());
            } catch (ArithmeticException overflow) {
                blockCache(diskId, cache, "Count overflow while checkpointing tape state", overflow);
                return false;
            }
            if (updated < 0) {
                blockCache(diskId, cache, "Negative count while checkpointing tape state", null);
                return false;
            }
            if (updated == 0) {
                snapshot.remove(tapeKey);
                continue;
            }

            AEItemKey aeKey = current.aeKey();
            if (aeKey == null) {
                ItemStack stack = decodeItemSafely(tapeKey.itemBytes());
                if (!stack.isEmpty()) aeKey = AEItemKey.of(stack);
            }
            snapshot.put(tapeKey, new StoredEntry(updated, aeKey));
        }

        if (!writeSnapshotAtomically(diskId, snapshot)) {
            cache.blocked = getCheckpointFile(diskId).exists();
            return false;
        }

        if (!truncateWalFile(diskId)) {
            LOGGER.warn("Failed to clear WAL for disk {}", diskId);
            cache.blocked = true;
            return false;
        }

        cache.entries.clear();
        cache.entries.putAll(snapshot);
        cache.deltaBuffer.clear();
        cache.totalBytes = calculateTotalBytes(cache.entries, Map.of());
        cache.dirty = false;
        if (!deleteCheckpointMarker(diskId)) {
            cache.blocked = true;
            return false;
        }
        return true;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Intentionally ignore cleanup failures.
        }
    }

    private static boolean writeSnapshotAtomically(UUID diskId, Map<TapeKey, StoredEntry> snapshot) {
        File target = getDiskFile(diskId);
        File temp = new File(target.getAbsolutePath() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(temp);
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fos))) {
            long written = 0L;
            for (var entry : new TreeMap<>(snapshot).entrySet()) {
                if (entry.getValue().count() <= 0) continue;
                byte[] data = entry.getKey().itemBytes();
                if (data == null || data.length == 0 || data.length > MAX_SERIALIZED_ITEM_BYTES) {
                    throw new IOException("Invalid serialized item length in tape snapshot");
                }
                long recordLength = Integer.BYTES + (long) data.length + Long.BYTES;
                if (written > MAX_DATABASE_FILE_BYTES - recordLength) {
                    throw new IOException("Tape snapshot exceeds the maximum database file size");
                }
                dos.writeInt(data.length);
                dos.write(data);
                dos.writeLong(entry.getValue().count());
                written += recordLength;
            }
            dos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            deleteQuietly(temp.toPath());
            LOGGER.warn("Flush/save failed for disk {}: {}", diskId, e.getMessage());
            return false;
        }

        SnapshotIdentity identity;
        try {
            identity = snapshotIdentity(temp);
            writeCheckpointMarker(diskId, identity);
        } catch (IOException e) {
            deleteQuietly(temp.toPath());
            LOGGER.warn("Failed to prepare tape checkpoint for disk {}: {}", diskId, e.getMessage());
            return false;
        }

        try {
            moveAtomically(temp.toPath(), target.toPath());
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to install tape snapshot for disk {}: {}", diskId, e.getMessage());
            // Keep the marker and WAL. Recovery can determine whether the move
            // took effect even when the filesystem reported an ambiguous error.
            return false;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static RecoveryDecision recoverCheckpoint(UUID diskId) {
        File marker = getCheckpointFile(diskId);
        if (!marker.exists()) return RecoveryDecision.REPLAY_WAL;

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(marker)))) {
            if (!CHECKPOINT_MAGIC.equals(input.readUTF())) {
                LOGGER.error("Tape {} has an unknown checkpoint marker; storage is blocked", diskId);
                return RecoveryDecision.BLOCKED;
            }
            SnapshotIdentity expected = new SnapshotIdentity(input.readLong(), input.readLong());
            File base = getDiskFile(diskId);
            if (expected.length() < 0 || expected.length() > MAX_DATABASE_FILE_BYTES
                    || expected.checksum() < 0 || expected.checksum() > 0xffffffffL
                    || input.read() != -1) {
                LOGGER.error("Tape {} has an invalid checkpoint marker; storage is blocked", diskId);
                return RecoveryDecision.BLOCKED;
            }
            boolean installed = base.exists() && base.length() == expected.length()
                    && snapshotIdentity(base).equals(expected);
            if (installed && !truncateWalFile(diskId)) return RecoveryDecision.BLOCKED;
            if (!deleteCheckpointMarker(diskId)) return RecoveryDecision.BLOCKED;
            deleteQuietly(new File(getDiskFile(diskId).getAbsolutePath() + ".tmp").toPath());
            return installed ? RecoveryDecision.SKIP_WAL : RecoveryDecision.REPLAY_WAL;
        } catch (IOException e) {
            LOGGER.error("Failed to recover tape checkpoint for {}; storage is blocked", diskId, e);
            return RecoveryDecision.BLOCKED;
        }
    }

    private static void writeCheckpointMarker(UUID diskId, SnapshotIdentity identity) throws IOException {
        File marker = getCheckpointFile(diskId);
        File temp = new File(marker.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(fos))) {
            output.writeUTF(CHECKPOINT_MAGIC);
            output.writeLong(identity.length());
            output.writeLong(identity.checksum());
            output.flush();
            fos.getFD().sync();
        }
        try {
            moveAtomically(temp.toPath(), marker.toPath());
        } catch (IOException error) {
            deleteQuietly(temp.toPath());
            throw error;
        }
    }

    private static SnapshotIdentity snapshotIdentity(File file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) crc.update(buffer, 0, read);
            }
        }
        return new SnapshotIdentity(file.length(), crc.getValue());
    }

    private static boolean truncateWalFile(UUID diskId) {
        try (FileOutputStream output = new FileOutputStream(getWalFile(diskId))) {
            output.getFD().sync();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to truncate tape WAL for {}", diskId, e);
            return false;
        }
    }

    private static boolean deleteCheckpointMarker(UUID diskId) {
        try {
            return Files.deleteIfExists(getCheckpointFile(diskId).toPath())
                    || !getCheckpointFile(diskId).exists();
        } catch (IOException e) {
            LOGGER.error("Failed to remove tape checkpoint marker for {}", diskId, e);
            return false;
        }
    }

    private static long checksum(byte[] data, long delta) {
        CRC32 crc = new CRC32();
        crc.update(data);
        crc.update(longToBytes(delta));
        return crc.getValue();
    }

    private static long calculateTotalBytes(
            Map<TapeKey, StoredEntry> entries,
            Map<TapeKey, Long> deltas) {
        Set<TapeKey> keys = new HashSet<>(entries.keySet());
        keys.addAll(deltas.keySet());

        long total = 0L;
        for (TapeKey key : keys) {
            long base = entries.getOrDefault(key, StoredEntry.EMPTY).count();
            long delta = deltas.getOrDefault(key, 0L);
            long count;
            try {
                count = Math.addExact(base, delta);
            } catch (ArithmeticException ignored) {
                count = delta > 0 ? Long.MAX_VALUE : 0L;
            }
            total = saturatedAdd(total, byteCost(key.itemBytes(), Math.max(0L, count)));
        }
        return total;
    }

    private static int getTypeCountLocked(TapeDriveCache cache) {
        Set<TapeKey> keys = new HashSet<>(cache.entries.keySet());
        keys.addAll(cache.deltaBuffer.keySet());
        int count = 0;
        for (TapeKey key : keys) {
            long base = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
            long delta = cache.deltaBuffer.getOrDefault(key, 0L);
            if (saturatedCount(base, delta) > 0) count++;
        }
        return count;
    }

    private static long byteCost(byte[] data, long count) {
        if (count <= 0 || data.length == 0) return 0L;
        // Exact integer form of round(serializedBytes * count * 0.75).
        long scaledItemBytes = (long) data.length * 3L;
        long groupsOfFour = count / 4L;
        long remainder = count % 4L;
        long tail = (remainder * scaledItemBytes + 2L) / 4L;
        if (groupsOfFour > (Long.MAX_VALUE - tail) / scaledItemBytes) return Long.MAX_VALUE;
        return groupsOfFour * scaledItemBytes + tail;
    }

    private static long maxInsertableLocked(
            UUID diskId,
            TapeDriveCache cache,
            byte[] itemBytes,
            long current,
            long requested
    ) {
        if (requested <= 0 || current == Long.MAX_VALUE) return 0;

        long bytesWithoutCurrent = Math.max(0L, cache.totalBytes - byteCost(itemBytes, current));
        long byteBudget = getByteLimit(diskId) - bytesWithoutCurrent;
        if (byteBudget < 0) return 0;

        long high = requested > Long.MAX_VALUE - current
                ? Long.MAX_VALUE
                : current + requested;
        long low = current;
        long best = current;
        while (low <= high) {
            long mid = low + ((high - low) >>> 1);
            if (byteCost(itemBytes, mid) <= byteBudget) {
                best = mid;
                if (mid == Long.MAX_VALUE) break;
                low = mid + 1;
            } else {
                if (mid == 0) break;
                high = mid - 1;
            }
        }
        return best - current;
    }

    private static long saturatedCount(long base, long delta) {
        try {
            return Math.max(0L, Math.addExact(base, delta));
        } catch (ArithmeticException ignored) {
            return delta > 0 ? Long.MAX_VALUE : 0L;
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (left >= Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
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

    private static File getCheckpointFile(UUID id) {
        return getFolder().resolve(id + ".checkpoint").toFile();
    }

    private static MinecraftServer requireActiveServer() {
        MinecraftServer server = activeServer;

        if (server == null) {
            server = ServerLifecycleHooks.getCurrentServer();
        }

        if (server == null) {
            throw new IllegalStateException(
                    "TapeDBManager requires an active Minecraft server"
            );
        }

        return server;
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to create directory: " + path,
                    e
            );
        }
    }

    private static Path getFolder() {
        MinecraftServer server = requireActiveServer();
        Path path = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("enderdrives")
                .resolve("TapeDrives");
        createDirectories(path);
        return path;
    }

    private static Path getExportFolder() {
        MinecraftServer server = requireActiveServer();
        Path path = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("enderdrives")
                .resolve("TapeDrives")
                .resolve("export");
        createDirectories(path);
        return path;
    }

    private static ScheduledExecutorService createExecutor() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    public static boolean exportToJson(UUID tapeId) {
        TapeDriveCache cache = getOrLoadForRead(tapeId);
        synchronized (cache.lock) {
            if (!persistCacheLocked(tapeId, cache)) return false;
        }

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
                jsonEntry.put("nbt", StackCodecHelper.encodeItemStack(
                        ServerLifecycleHooks.getCurrentServer().registryAccess(), stack).toString());
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
                        CompoundTag tag = TagParser.parseCompoundFully(currentNBT.toString());
                        var provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
                        ItemStack stack = StackCodecHelper.decodeItemStack(provider, tag);
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

        CompletableFuture<TapeDriveCache> pending = pendingLoads.get(tapeId);
        if (pending != null) {
            try {
                pending.join();
            } catch (CompletionException e) {
                LOGGER.error("Failed waiting for tape {} to finish loading", tapeId, e);
                return false;
            }
        }

        // Flush and detach the old cache before replacing its base file. Doing
        // this afterward would overwrite the imported data with the old cache.
        getOrLoadForRead(tapeId);
        releaseFromRAM(tapeId);
        if (activeCaches.containsKey(tapeId)) {
            LOGGER.error("Could not release tape {} before import", tapeId);
            return false;
        }

        try (FileOutputStream ignored = new FileOutputStream(walFile)) {
            // The old state is now in the base file, so the WAL can be reset
            // before atomically installing the imported snapshot.
        } catch (IOException e) {
            LOGGER.error("Failed to reset WAL before importing tape {}: {}", tapeId, e.getMessage());
            return false;
        }

        Map<TapeKey, StoredEntry> imported = new HashMap<>();
        for (int i = 0; i < serializedItems.size(); i++) {
            byte[] data = serializedItems.get(i);
            long count = counts.get(i);
            if (count <= 0) continue;

            ItemStack stack = deserializeItemStackFromBytes(data);
            if (stack.isEmpty()) continue;
            TapeKey tapeKey = new TapeKey(data);
            StoredEntry previous = imported.getOrDefault(tapeKey, StoredEntry.EMPTY);
            long merged;
            try {
                merged = Math.addExact(previous.count(), count);
            } catch (ArithmeticException ignored) {
                merged = Long.MAX_VALUE;
            }
            imported.put(tapeKey, new StoredEntry(merged, AEItemKey.of(stack)));
        }

        if (!writeSnapshotAtomically(tapeId, imported)) {
            LOGGER.error("Failed to write new binary DB for tape {}", tapeId);
            return false;
        }
        if (!deleteCheckpointMarker(tapeId)) return false;

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
        File checkpoint = getCheckpointFile(tapeId);
        boolean deleted = false;

        if (bin.exists()) deleted |= bin.delete();
        if (wal.exists()) deleted |= wal.delete();
        if (checkpoint.exists()) deleted |= checkpoint.delete();

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
        private final Object lock = new Object();
        private final long generation;

        public final ConcurrentHashMap<TapeKey, StoredEntry> entries = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<TapeKey, Long> deltaBuffer = new ConcurrentHashMap<>();

        public volatile long lastAccessed = System.currentTimeMillis();
        public volatile long totalBytes = 0;

        private boolean dirty;
        private boolean blocked;

        private TapeDriveCache(long generation) {
            this.generation = generation;
        }
    }

    public static boolean isOperational(UUID diskId) {
        TapeDriveCache cache = getCacheSafe(diskId);
        if (cache == null) {
            if (ServerLifecycleHooks.getCurrentServer() == null) return false;
            cache = getOrLoadForRead(diskId);
        }
        synchronized (cache.lock) {
            return !cache.blocked;
        }
    }

    private enum RecoveryDecision {
        REPLAY_WAL,
        SKIP_WAL,
        BLOCKED
    }

    private record SnapshotIdentity(long length, long checksum) {}

    private record UnreadableRecord(long count, byte[] itemBytes) {}

    private static void log(String format, Object... args) {
        if (debugLog) LOGGER.info(String.format(format, args));
    }
}
