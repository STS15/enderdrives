package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import com.sts15.enderdrives.config.serverConfig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import static com.sts15.enderdrives.inventory.EnderDiskInventory.deserializeItemStackFromBytes;

public class EnderDBManager {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    public static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();
    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    public static final ConcurrentHashMap<AEKey, Long> deltaBuffer = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CachedCount> itemCountCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> DRIVE_LOCKS = new ConcurrentHashMap<>();
    private static final Object DELTA_BUFFER_LOCK = new Object();
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    public static volatile boolean running = true;
    private static volatile boolean dirty = false;
    private static long lastCommitTime = System.currentTimeMillis();
    private static long lastDbCommitTime = System.currentTimeMillis();
    private static final AtomicLong totalItemsWritten = new AtomicLong(0);
    private static final AtomicLong totalCommits = new AtomicLong(0);
    private static final ForkJoinPool SHARED_PARALLEL_POOL = new ForkJoinPool(Math.min(4, Runtime.getRuntime().availableProcessors()));
    static int mergeThreshold = serverConfig.END_DB_MERGE_BUFFER_THRESHOLD.get();
    static long minCommit = serverConfig.END_DB_MIN_COMMIT_INTERVAL_MS.get();
    static long maxCommit = serverConfig.END_DB_MAX_COMMIT_INTERVAL_MS.get();
    static long minDbCommit = serverConfig.END_DB_MIN_DB_COMMIT_INTERVAL_MS.get();
    static long maxDbCommit = serverConfig.END_DB_MAX_DB_COMMIT_INTERVAL_MS.get();
    static boolean debugLog = serverConfig.END_DB_DEBUG_LOG.get();
    public static volatile boolean isShutdown = false;
    private static Thread commitThread;

// ==== Public API ====

    /**
     * Initializes the EnderDB system, loading the database and replaying WAL logs.
     * Sets up the background commit thread and registers a shutdown hook.
     */
    public static void init() {
        try {
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT).resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);
            dbFile = worldDir.resolve("enderdrives.bin").toFile();
            currentWAL = worldDir.resolve("enderdrives.wal").toFile();
            migrateOldRecords();
            openWALStream();
            replayWALs();
            loadDatabase();
            startBackgroundCommit();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Shutdown hook triggered.");
                shutdown();
            }));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Shuts down the EnderDB system gracefully by flushing buffers, committing data, and closing WAL streams.
     */
    public static void shutdown() {
        if (isShutdown) return;
        isShutdown = true;
        running = false;

        try {
            if (commitThread != null && commitThread.isAlive()) {
                LOGGER.info("Waiting for EnderDB background thread to finish...");
                commitThread.join(2000);
            }
            synchronized (commitLock) {
                flushDeltaBuffer();
                if (!dbMap.isEmpty()) {
                    commitDatabase();
                }
                truncateCurrentWAL();
                closeWALStream();
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Exception during EnderDBManager shutdown: ", e);
        } finally {
            dbMap.clear();
            deltaBuffer.clear();
            itemCountCache.clear();
            walQueue.clear();
            totalItemsWritten.set(0);
            totalCommits.set(0);
            isShutdown = false;
            running = true;
            dirty = false;
            lastCommitTime = System.currentTimeMillis();
            lastDbCommitTime = System.currentTimeMillis();
            LOGGER.info("[EnderDBManager] Shutdown complete and ready for re-init.");
        }
    }


    /**
     * Saves an item delta into the database, merging counts by item key.
     *
     * @param scopePrefix The scope name (e.g. player or global).
     * @param freq        The frequency ID associated with the item.
     * @param itemNbtBinary Serialized ItemStack data.
     * @param deltaCount  The count delta to apply (positive or negative).
     */
    public static void saveItem(String scopePrefix, int freq, byte[] itemNbtBinary, long deltaCount) {
        AEKey key = new AEKey(scopePrefix, freq, itemNbtBinary);
        deltaBuffer.merge(key, deltaCount, Long::sum);

        if (deltaBuffer.size() >= mergeThreshold ) {
            flushDeltaBuffer();
        }
    }

    /**
     * Retrieves the stored count of an item in the database.
     *
     * @param scopePrefix The scope name.
     * @param freq        The frequency ID.
     * @param keyBytes The serialized item key.
     * @return The current stored count for the item.
     */
    public static long getItemCount(String scopePrefix, int freq, byte[] keyBytes) {
        AEKey key = new AEKey(scopePrefix, freq, keyBytes);
        long committed = dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
        long pending = deltaBuffer.getOrDefault(key, 0L);
        return committed + pending;
    }


    /**
     * Clears all entries for a given frequency and scope.
     *
     * @param scopePrefix The scope name.
     * @param frequency   The frequency ID to clear.
     */
    public static void clearFrequency(String scopePrefix, int frequency) {
        AEKey from = new AEKey(scopePrefix, frequency, new byte[0]);
        AEKey to = new AEKey(scopePrefix, frequency + 1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> sub = dbMap.subMap(from, true, to, false);
        int removed = sub.size();
        sub.clear();
        log("Cleared frequency %d for scope %s (%d entries)", frequency, scopePrefix, removed);
    }

    /**
     * Gets the number of unique item types stored for a given frequency and scope.
     *
     * @param scopePrefix The scope name.
     * @param freq        The frequency ID.
     * @return The number of unique item keys.
     */
    public static int getTypeCount(String scopePrefix, int freq) {
        AEKey from = new AEKey(scopePrefix, freq, new byte[0]);
        AEKey to = new AEKey(scopePrefix, freq + 1, new byte[0]);
        return dbMap.subMap(from, true, to, false).size();
    }

    /**
     * Queries all items under a given frequency and scope, returning their keys and counts.
     *
     * @param scopePrefix The scope name.
     * @param freq        The frequency ID.
     * @return A list of matching cache entries.
     */
    public static List<AEKeyCacheEntry> queryItemsByFrequency(String scopePrefix, int freq) {
        AEKey from = new AEKey(scopePrefix, freq, new byte[0]);
        AEKey to = new AEKey(scopePrefix, freq + 1, new byte[0]);

        NavigableMap<AEKey, StoredEntry> subMap = dbMap.subMap(from, true, to, false);

        try {
            return SHARED_PARALLEL_POOL.submit(() ->
                    subMap.entrySet()
                            .parallelStream()
                            .map(entry -> {
                                AEItemKey aeKey = entry.getValue().aeKey();
                                if (aeKey != null) {
                                    return new AEKeyCacheEntry(entry.getKey(), aeKey, entry.getValue().count());
                                }
                                return null;
                            })
                            .filter(Objects::nonNull)
                            .toList()
            ).get();
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }


    public static long getTotalItemCount(String scopePrefix, int frequency) {
        String key = scopePrefix + "|" + frequency;
        CachedCount cached = itemCountCache.get(key);
        long now = System.currentTimeMillis();

        if (cached == null || (now - cached.timestamp) >= 1000) {
            long newCount = calculateTotalItemCount(scopePrefix, frequency);
            itemCountCache.put(key, new CachedCount(newCount, now));
            log("Recalculated item count: scope=%s freq=%d total=%d", scopePrefix, frequency, newCount);
            return newCount;
        }

        log("Using cached item count: scope=%s freq=%d total=%d", scopePrefix, frequency, cached.count);
        return cached.count;
    }

    private static long calculateTotalItemCount(String scopePrefix, int frequency) {
        List<AEKeyCacheEntry> entries = queryItemsByFrequency(scopePrefix, frequency);
        try {
            return SHARED_PARALLEL_POOL.submit(() ->
                    entries.parallelStream()
                            .mapToLong(AEKeyCacheEntry::count)
                            .sum()
            ).get();
        } catch (Exception e) {
            e.printStackTrace();
            return 0L;
        }
    }

    public static List<ItemStack> getTopStacks(String scopePrefix, int frequency, int max) {
        List<AEKeyCacheEntry> entries = queryItemsByFrequency(scopePrefix, frequency);

        try {
            return SHARED_PARALLEL_POOL.submit(() ->
                    entries.stream()
                            .sorted(Comparator.comparingLong(AEKeyCacheEntry::count).reversed())
                            .limit(max)
                            .parallel()
                            .map(e -> e.aeKey().toStack((int) Math.min(e.count(), Integer.MAX_VALUE)))
                            .toList()
            ).get();
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private record CachedCount(long count, long timestamp) {}

    /**
     * Commits the current state of the database to disk, flushing all in-memory changes.
     */
    public static void commitDatabase() {
        try {
            File temp = new File(dbFile.getAbsolutePath() + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp), 1024 * 512))) {
                List<byte[]> records = parallelCall(() ->
                        dbMap.entrySet().parallelStream().map(entry -> {
                            try {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream tmpDos = new DataOutputStream(baos);
                                AEKey key = entry.getKey();
                                long count = entry.getValue().count();
                                tmpDos.writeUTF(key.scope());
                                tmpDos.writeInt(key.freq());
                                tmpDos.writeInt(key.itemBytes().length);
                                tmpDos.write(key.itemBytes());
                                tmpDos.writeLong(count);
                                tmpDos.close();
                                return baos.toByteArray();
                            } catch (IOException e) {
                                e.printStackTrace();
                                return null;
                            }
                        }).filter(Objects::nonNull).toList(), List.of()
                );

                for (byte[] record : records) {
                    dos.write(record);
                }
            }

            Files.move(temp.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            log("Database committed successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

// ==== Public Getters / Stats ====

    public static AtomicLong getTotalItemsWritten() { return totalItemsWritten; }
    public static AtomicLong getTotalCommits() { return totalCommits; }
    public static int getWalQueueSize() { return walQueue.size(); }
    public static int getDatabaseSize() { return dbMap.size(); }
    public static long getDatabaseFileSizeBytes() { return dbFile.exists() ? dbFile.length() : 0; }

// ==== Background Thread Handling ====

    /**
     * Starts the background commit thread that flushes WAL entries and periodically writes the database to disk.
     */
    private static void startBackgroundCommit() {
        Thread t = new Thread(() -> {
            List<byte[]> batch = new ArrayList<>();
            while (running) {
                try {
                    flushDeltaBuffer();

                    int walSizeBytes = walQueue.stream().mapToInt(arr -> arr.length + Long.BYTES + Integer.BYTES).sum();
                    int dynamicBatchSize = Math.min(50_000, Math.max(1_000, walQueue.size() / 10));

                    if (walSizeBytes <= 5 * 1024 * 1024) {
                        walQueue.drainTo(batch);
                    } else {
                        walQueue.drainTo(batch, dynamicBatchSize);
                    }

                    long now = System.currentTimeMillis();

                    boolean minIntervalElapsed = now - lastCommitTime >= minCommit ;
                    boolean maxIntervalElapsed = now - lastCommitTime >= maxCommit ;

                    boolean shouldCommit = (!batch.isEmpty() && minIntervalElapsed)
                            || walQueue.size() >= dynamicBatchSize * 2
                            || maxIntervalElapsed;

                    if (shouldCommit) {
                        synchronized (commitLock) {
                            for (byte[] entry : batch) {
                                walWriter.writeInt(entry.length);
                                walWriter.write(entry);
                                walWriter.writeLong(checksum(entry));
                                totalItemsWritten.incrementAndGet();
                                lastCommitTime = now;
                            }
                            walWriter.flush();

                            if (!batch.isEmpty()) {
                                log("Committed %d WAL entries. TotalItems=%d", batch.size(), totalItemsWritten);
                            }
                            boolean minDbCommitElapsed = now - lastDbCommitTime >= minDbCommit ;
                            boolean maxDbCommitElapsed = now - lastDbCommitTime >= maxDbCommit ;
                            if (dirty && (batch.size() > 1000 || maxDbCommitElapsed || minDbCommitElapsed)) {
                                commitDatabase();
                                truncateCurrentWAL();
                                lastDbCommitTime = now;
                                totalCommits.incrementAndGet();
                            }
                        }
                        batch.clear();
                    }

                    long lastReplayCheck = System.currentTimeMillis();
                    final long REPLAY_IDLE_INTERVAL_MS = 10_000;
                    if (walQueue.isEmpty()) {
                        if (now - lastReplayCheck >= REPLAY_IDLE_INTERVAL_MS) {
                            lastReplayCheck = now;
                            try {
                                File dir = currentWAL.getParentFile();
                                File[] rotatedWALs = dir.listFiles((d, name) -> name.matches("enderdrives\\.wal\\.\\d+"));
                                if (rotatedWALs != null && rotatedWALs.length > 0) {
                                    Arrays.sort(rotatedWALs, Comparator.comparing(File::getName));
                                    for (File wal : rotatedWALs) {
                                        if (wal.length() > 0) {
                                            log("Idle Replay: Processing %s", wal.getName());
                                            replayAndDeleteWAL(wal);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    Thread.sleep(5);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "EnderDB-CommitThread");

        t.setDaemon(true);
        t.start();
    }

// ==== WAL Handling & Processing ====

    /**
     * Flushes the delta buffer into the WAL queue, preparing it for commit.
     */
    public static void flushDeltaBuffer() {
        List<Map.Entry<AEKey, Long>> snapshot = new ArrayList<>(deltaBuffer.entrySet());
        deltaBuffer.clear();

        List<byte[]> entries = parallelCall(() ->
                snapshot.parallelStream()
                        .filter(e -> e.getValue() != 0)
                        .map(entry -> {
                            try {
                                AEKey key = entry.getKey();
                                long delta = entry.getValue();
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream(baos);
                                dos.writeUTF(key.scope());
                                dos.writeInt(key.freq());
                                dos.writeInt(key.itemBytes().length);
                                dos.write(key.itemBytes());
                                dos.writeLong(delta);
                                dos.close();
                                return baos.toByteArray();
                            } catch (IOException e) {
                                e.printStackTrace();
                                return null;
                            }
                        }).filter(Objects::nonNull)
                        .toList(), List.of()
        );

        for (byte[] walEntry : entries) {
            walQueue.add(walEntry);
            applyBinaryOperation(walEntry);
        }
    }


    /**
     * Applies a binary WAL entry to the in-memory database map.
     *
     * @param data Serialized WAL operation.
     */
    private static void applyBinaryOperation(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            String scopePrefix = dis.readUTF();
            int freq = dis.readInt();
            int keyLen = dis.readInt();
            byte[] keyBytes = new byte[keyLen];
            dis.readFully(keyBytes);
            long delta = dis.readLong();
            AEKey key = new AEKey(scopePrefix, freq, keyBytes);
            long oldVal = dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
            long newVal = oldVal + delta;
            if (newVal <= 0) {
                dbMap.remove(key);
            } else {
                AEItemKey aeKey = null;
                try {
                    ItemStack stack = deserializeItemStackFromBytes(keyBytes);
                    if (!stack.isEmpty()) {
                        aeKey = AEItemKey.of(stack);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (aeKey != null) {
                    dbMap.put(key, new StoredEntry(newVal, aeKey));
                } else {
                    dbMap.put(key, new StoredEntry(newVal, null));
                }
            }
            log("Applying WAL: key=%s delta=%d old=%d new=%d", key, delta, oldVal, newVal);
            dirty = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Truncates (clears and resets) the current WAL file to a clean state.
     */
    private static void truncateCurrentWAL() {
        try {
            closeWALStream();
            new FileOutputStream(currentWAL).close();
            openWALStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Replays the current WAL and any rotated WAL files, applying their entries to memory.
     *
     * @throws IOException if file access fails.
     */
    private static void replayWALs() throws IOException {
        if (currentWAL.exists()) {
            replayAndDeleteWAL(currentWAL);
        }
        File dir = currentWAL.getParentFile();
        File[] rotatedWALs = dir.listFiles((d, name) ->
                name.startsWith("enderdrives.wal.") && name.matches(".*\\.\\d+$"));
        if (rotatedWALs != null) {
            Arrays.sort(rotatedWALs);
            for (File rotated : rotatedWALs) {
                replayAndDeleteWAL(rotated);
            }
        }
        truncateCurrentWAL();
    }

    /**
     * Replays a specific WAL file and deletes it after successful processing.
     *
     * @param walFile The WAL file to replay.
     */
    private static void replayAndDeleteWAL(File walFile) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(walFile)))) {
            while (true) {
                try {
                    int length = dis.readInt();
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    long storedChecksum = dis.readLong();
                    if (checksum(data) != storedChecksum) {
                        log("Checksum mismatch for record in %s", walFile.getName());
                        continue;
                    }
                    log("Replaying record from %s: data length=%d", walFile.getName(), length);
                    applyBinaryOperation(data);
                } catch (EOFException eof) {
                    break;
                }
            }
            if (walFile.delete()) {
                log("Deleted WAL file %s", walFile.getName());
            } else {
                log("Failed to delete WAL file %s", walFile.getName());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

// ==== File & Stream Management ====

    /**
     * Opens the WAL stream for appending new entries.
     *
     * @throws IOException if opening fails.
     */
    private static void openWALStream() throws IOException {
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter = new DataOutputStream(new BufferedOutputStream(walFileStream));
    }

    /**
     * Closes the current WAL output stream.
     *
     * @throws IOException if closing fails.
     */
    private static void closeWALStream() throws IOException {
        walWriter.close();
        walFileStream.close();
    }

    /**
     * Loads the database from disk into memory, if the file exists.
     *
     * @throws IOException if reading fails.
     */
    private static void loadDatabase() throws IOException {
        if (!dbFile.exists()) {
            LOGGER.info("No database file found.");
            return;
        }
        long fileLength = dbFile.length();
        if (fileLength == 0) {
            return;
        }
        dbMap.clear();
        int recordCount = 0;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)))) {
            while (true) {
                try {
                    String scope = dis.readUTF();
                    int freq = dis.readInt();
                    int keyLen = dis.readInt();
                    byte[] keyBytes = new byte[keyLen];
                    dis.readFully(keyBytes);
                    long count = dis.readLong();
                    AEKey key = new AEKey(scope, freq, keyBytes);
                    // Reconstruct the AEItemKey from the stored bytes.
                    AEItemKey aeKey = null;
                    try {
                        ItemStack stack = deserializeItemStackFromBytes(keyBytes);
                        if (!stack.isEmpty()) {
                            aeKey = AEItemKey.of(stack);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    dbMap.put(key, new StoredEntry(count, aeKey));
                    recordCount++;
                } catch (Exception ex) {
                    break;
                }
            }
        }
    }

// ==== Internal DB Tools ====

    /**
     * Calculates a checksum for a byte array using CRC32.
     *
     * @param data The byte array to checksum.
     * @return The CRC32 value.
     */
    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Logs debug messages to console if DEBUG_LOG is enabled.
     *
     * @param format The message format string.
     * @param args   Format arguments.
     */
    private static void log(String format, Object... args) {
        if (debugLog) {
            LOGGER.debug("[EnderDiskInventory] " + format, args);
        }
    }

    /**
     * Migrates any legacy records with malformed or missing scope names to the "global" scope.
     */
    private static void migrateOldRecords() {
        List<Map.Entry<AEKey, StoredEntry>> toMigrate = parallelCall(() ->
                dbMap.entrySet().parallelStream()
                        .filter(entry -> {
                            String scope = entry.getKey().scope();
                            return scope == null || scope.isEmpty() || (!scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global"));
                        })
                        .toList(), List.of()
        );

        if (toMigrate.isEmpty()) return;

        LOGGER.info("Detected {} old-format records. Migrating to global scope...", toMigrate.size());

        for (Map.Entry<AEKey, StoredEntry> entry : toMigrate) {
            AEKey oldKey = entry.getKey();
            StoredEntry value = entry.getValue();
            AEKey newKey = new AEKey("global", oldKey.freq(), oldKey.itemBytes());
            long existing = dbMap.getOrDefault(newKey, new StoredEntry(0L, null)).count();
            dbMap.put(newKey, new StoredEntry(existing + value.count(), null));
            dbMap.remove(oldKey);
        }

        dirty = true;
    }


    private static <T> T parallelCall(Callable<T> task, T fallback) {
        try {
            return SHARED_PARALLEL_POOL.submit(task).get();
        } catch (Exception e) {
            e.printStackTrace();
            return fallback;
        }
    }

    public static boolean isKnownItem(String scopePrefix, int frequency, byte[] keyBytes) {
        AEKey key = new AEKey(scopePrefix, frequency, keyBytes);
        return dbMap.containsKey(key) || deltaBuffer.containsKey(key);
    }

    public static Object getDriveLock(String scopePrefix, int frequency) {
        return DRIVE_LOCKS.computeIfAbsent(scopePrefix + "|" + frequency, k -> new Object());
    }

    private static Object getDriveLockFromWAL(byte[] walEntry) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(walEntry))) {
            String scope = dis.readUTF();
            int freq = dis.readInt();
            return getDriveLock(scope, freq);
        } catch (IOException e) {
            e.printStackTrace();
            return new Object();
        }
    }

    private static Object getDriveLockForAllDelta() {
        return DELTA_BUFFER_LOCK;
    }

}