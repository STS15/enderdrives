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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static com.sts15.enderdrives.inventory.EnderDiskInventory.deserializeItemStackFromBytes;

public class EnderDBManager {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    public static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();
    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    private static final ConcurrentHashMap<String, CachedCount> itemCountCache = new ConcurrentHashMap<>();
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    public static volatile boolean running = true, dirty = false;
    private static Thread commitThread = null;
    private static final AtomicLong totalItemsWritten = new AtomicLong(0);
    private static final AtomicLong totalCommits = new AtomicLong(0);
    private static final ForkJoinPool SHARED_PARALLEL_POOL = new ForkJoinPool(Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int MERGE_BUFFER_THRESHOLD   = serverConfig.END_DB_MERGE_BUFFER_THRESHOLD.get();
    private static final long MIN_WAL_COMMIT_MS       = serverConfig.END_DB_MIN_COMMIT_INTERVAL_MS.get();
    private static final long MAX_WAL_COMMIT_MS       = serverConfig.END_DB_MAX_COMMIT_INTERVAL_MS.get();
    private static final long MIN_DB_COMMIT_MS        = serverConfig.END_DB_MIN_DB_COMMIT_INTERVAL_MS.get();
    private static final long MAX_DB_COMMIT_MS        = serverConfig.END_DB_MAX_DB_COMMIT_INTERVAL_MS.get();
    private static final boolean DEBUG_LOG            = serverConfig.END_DB_DEBUG_LOG.get();
    private static long lastWalCommitTime = System.currentTimeMillis();
    private static long lastDbCommitTime  = System.currentTimeMillis();

// ==== Public API ====

    public static void init() {
        try {
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT)
                    .resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);

            dbFile = worldDir.resolve("enderdrives.bin").toFile();
            currentWAL = worldDir.resolve("enderdrives.wal").toFile();

            migrateOldRecords();
            loadDatabase();
            replayWALs();
            openWALStream();
            startBackgroundCommit();

            Runtime.getRuntime().addShutdownHook(new Thread(EnderDBManager::shutdown));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void clearRAMCaches() {
        synchronized (commitLock) {
            dbMap.clear();
        }
        itemCountCache.clear();

        log("[clearRAMCaches] RAM caches cleared successfully.");
    }

    public static void shutdown() {
        running = false;

        synchronized (commitLock) {
            flushWALQueue();
            if (!dbMap.isEmpty()) {
                commitDatabase();
            }
            truncateCurrentWAL();
            try {
                closeWALStream();
            } catch (IOException ignored) {}
            clearRAMCaches();
        }

        if (commitThread != null) {
            try {
                commitThread.join(500);
            } catch (InterruptedException ignored) {}
            commitThread = null;
        }

    }

    /**
     * Saves an item delta into the database, merging counts by item key.
     *
     * @param scopePrefix The scope name (e.g. player or global).
     * @param freq        The frequency ID associated with the item.
     * @param itemBytes Serialized ItemStack data.
     * @param deltaCount  The count delta to apply (positive or negative).
     */
    public static void saveItem(String scopePrefix, int freq, byte[] itemBytes, long deltaCount) {
        AEKey key = new AEKey(scopePrefix, freq, itemBytes);

        synchronized (commitLock) {
            dbMap.compute(key, (k, existing) -> {
                long newCount = (existing == null ? 0L : existing.count()) + deltaCount;
                if (newCount <= 0) return null;

                AEItemKey aeKey = null;
                try {
                    ItemStack s = deserializeItemStackFromBytes(itemBytes);
                    if (!s.isEmpty()) aeKey = AEItemKey.of(s);
                } catch (Exception ignored) {}

                return new StoredEntry(newCount, aeKey);
            });

            try (var baos = new ByteArrayOutputStream();
                 var dos = new DataOutputStream(baos)) {
                dos.writeUTF(scopePrefix);
                dos.writeInt(freq);
                dos.writeInt(itemBytes.length);
                dos.write(itemBytes);
                dos.writeLong(deltaCount);
                walQueue.add(baos.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }

            dirty = true;
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
        return dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
    }


    /**
     * Like getTypeCount, but also counts any keys still sitting in deltaBuffer
     */
    public static int getTypeCountInclusive(String scope, int freq) {
        AEKey from = new AEKey(scope, freq, new byte[0]);
        AEKey to   = new AEKey(scope, freq + 1, new byte[0]);
        return dbMap.subMap(from, true, to, false).size();
    }


    /**
     * Like getTotalItemCount, but sums committed + pending
     */
    public static long getTotalItemCountInclusive(String scope, int freq) {
        long total = 0L;
        AEKey from = new AEKey(scope, freq, new byte[0]);
        AEKey to   = new AEKey(scope, freq + 1, new byte[0]);
        for (Map.Entry<AEKey, StoredEntry> e : dbMap.subMap(from, true, to, false).entrySet()) {
            total += e.getValue().count();
        }
        return total;
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
        log("[clearFrequency] Cleared frequency %d for scope %s (%d entries)", frequency, scopePrefix, removed);
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
     * Queries all items for a scope/freq, merging committed + pending.
     */
    public static List<AEKeyCacheEntry> queryItemsByFrequency(String scopePrefix, int freq) {
        AEKey lo = new AEKey(scopePrefix, freq,   new byte[0]);
        AEKey hi = new AEKey(scopePrefix, freq+1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> committed = dbMap.subMap(lo, true, hi, false);

        List<AEKeyCacheEntry> result = new ArrayList<>();
        for (var e : committed.entrySet()) {
            long cnt = e.getValue().count();
            if (cnt <= 0) continue;
            AEKey k = e.getKey();
            AEItemKey aek = e.getValue().aeKey();
            if (aek != null) {
                result.add(new AEKeyCacheEntry(k, aek, cnt));
            }
        }
        return result;
    }

    public static long getTotalItemCount(String scopePrefix, int frequency) {
        String key = scopePrefix + "|" + frequency;
        CachedCount cached = itemCountCache.get(key);
        long now = System.currentTimeMillis();

        if (cached == null || (now - cached.timestamp) >= 1000) {
            long newCount = calculateTotalItemCount(scopePrefix, frequency);
            itemCountCache.put(key, new CachedCount(newCount, now));
            log("[getTotalItemCount] Recalculated item count: scope=%s freq=%d total=%d", scopePrefix, frequency, newCount);
            return newCount;
        }

        log("[getTotalItemCount] Using cached item count: scope=%s freq=%d total=%d", scopePrefix, frequency, cached.count);
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
                    new BufferedOutputStream(new FileOutputStream(temp), 512 * 1024))) {
                // header
                Properties props = new Properties();
                String ver = "undefined";
                try (InputStream in = EnderDBManager.class.getResourceAsStream("/mod_version.properties")) {
                    if (in != null) { props.load(in); ver = props.getProperty("mod.version"); }
                }
                dos.writeUTF("EDB1");
                dos.writeUTF(ver);
                dos.writeInt(1);
                dos.writeLong(System.currentTimeMillis());

                // write entries
                List<byte[]> recs = parallelCall(() ->
                        dbMap.entrySet().parallelStream().map(e -> {
                            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                 DataOutputStream tmp = new DataOutputStream(baos)) {
                                AEKey k = e.getKey();
                                tmp.writeUTF(k.scope()); tmp.writeInt(k.freq());
                                tmp.writeInt(k.itemBytes().length); tmp.write(k.itemBytes());
                                tmp.writeLong(e.getValue().count());
                                return baos.toByteArray();
                            } catch (IOException ex) {
                                ex.printStackTrace(); return null;
                            }
                        }).filter(Objects::nonNull).toList(), List.of());
                for (byte[] r : recs) dos.write(r);
            }
            Files.move(temp.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            log("[commitDatabase] Database committed successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

// ==== Public Getters / Stats ====

    public static AtomicLong getTotalItemsWritten() { return totalItemsWritten; }
    public static AtomicLong getTotalCommits() { return totalCommits; }
    public static int getDatabaseSize() { return dbMap.size(); }
    public static long getDatabaseFileSizeBytes() { return dbFile.exists() ? dbFile.length() : 0; }

// ==== Background Thread Handling ====

    /**
     * Starts the background commit thread that flushes WAL entries and periodically writes the database to disk.
     */
    private static void startBackgroundCommit() {

        if (commitThread != null && commitThread.isAlive()) {
            log("[startBackgroundCommit] Commit thread already running; skipping new launch.");
            return;
        }

        running = true;

        commitThread = new Thread(() -> {
            log("[startBackgroundCommit] Background WAL commit thread starting...");
            try {
                final int WAL_BATCH_SIZE = 100;  // how many entries to drain at once
                long nextWalTime = System.currentTimeMillis() + MIN_WAL_COMMIT_MS;
                long nextDbTime  = System.currentTimeMillis() + MIN_DB_COMMIT_MS;

                while (running) {
                    try {
                        long now = System.currentTimeMillis();
                        int queueSize = walQueue.size();

                        // === WAL FLUSH LOGIC ===
                        boolean timeToFlushWAL = now >= nextWalTime;
                        boolean queueThresholdMet = queueSize >= WAL_BATCH_SIZE;

                        if ((timeToFlushWAL || queueThresholdMet) && queueSize > 0) {
                            synchronized (commitLock) {
                                List<byte[]> batch = new ArrayList<>();
                                walQueue.drainTo(batch, WAL_BATCH_SIZE);

                                if (!batch.isEmpty()) {
                                    log("[startBackgroundCommit] WAL flush: entries={}, (time={}, threshold={})",
                                            batch.size(), timeToFlushWAL, queueThresholdMet);

                                    for (byte[] rec : batch) {
                                        walWriter.writeInt(rec.length);
                                        walWriter.write(rec);
                                        walWriter.writeLong(checksum(rec));
                                        totalItemsWritten.incrementAndGet();
                                    }
                                    walWriter.flush();
                                    lastWalCommitTime = now;
                                    log("[startBackgroundCommit] WAL flushed, totalItemsWritten={}", totalItemsWritten.get());
                                }
                            }

                            nextWalTime = now + MIN_WAL_COMMIT_MS;
                        }

                        // === DB COMMIT LOGIC ===
                        if (dirty && now >= nextDbTime) {
                            synchronized (commitLock) {
                                log("[startBackgroundCommit] DB checkpoint: entries={} dirty={}", dbMap.size(), dirty);
                                commitDatabase();
                                truncateCurrentWAL();
                                lastDbCommitTime = now;
                                totalCommits.incrementAndGet();
                                dirty = false;
                                log("[startBackgroundCommit] DB committed, totalCommits={}", totalCommits.get());
                            }
                            nextDbTime = now + MIN_DB_COMMIT_MS;
                        }

                        Thread.sleep(100);
                    } catch (Exception e) {
                        LOGGER.error("Background commit error", e);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[EnderDB] WAL Commit thread crashed", e);
            }
        }, "EnderDB-CommitThread");

        commitThread.setDaemon(true);
        commitThread.start();
    }



// ==== WAL Handling & Processing ====

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

    public static void flushWALQueue() {
        if (walQueue.isEmpty()) return;
        try {
            log("[FlushWALQueue] Flushing WAL queue with {} entries on shutdown", walQueue.size());
            List<byte[]> batch = new ArrayList<>();
            walQueue.drainTo(batch);
            for (byte[] rec : batch) {
                walWriter.writeInt(rec.length);
                walWriter.write(rec);
                walWriter.writeLong(checksum(rec));
            }
            walWriter.flush();
        } catch (IOException e) {
            LOGGER.error("Error flushing WAL queue during shutdown", e);
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
        if (currentWAL == null) {
            throw new IllegalStateException("currentWAL file is not set!");
        }
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter = new DataOutputStream(new BufferedOutputStream(walFileStream));
    }

    /**
     * Closes the current WAL output stream.
     *
     * @throws IOException if closing fails.
     */
    private static void closeWALStream() throws IOException {
        if (walWriter != null) {
            walWriter.close();
            walWriter = null;
        }
        if (walFileStream != null) {
            walFileStream.close();
            walFileStream = null;
        }
    }

    /**
     * Loads the database from disk into memory, if the file exists.
     *
     * @throws IOException if reading fails.
     */
    private static void loadDatabase() throws IOException {
        if (!dbFile.exists() || dbFile.length() == 0) return;

        Properties props = new Properties();
        String curVer = "undefined";
        try (InputStream in = EnderDBManager.class.getResourceAsStream("/mod_version.properties")) {
            if (in != null) { props.load(in); curVer = props.getProperty("mod.version"); }
        }

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)))) {
            dis.mark(128);
            boolean hasHeader = false;
            String header = dis.readUTF();
            if ("EDB1".equals(header)) {
                hasHeader = true;
                String fileVer = dis.readUTF();
                int fmt = dis.readInt();
                long ts = dis.readLong();
                log("Loaded EDB1 header ver={} fmt={} ts={}", fileVer, fmt, new Date(ts));
                if (!fileVer.equals(curVer)) backupDatabaseFile(fileVer);
            } else {
                dis.reset();
                backupDatabaseFile("0.0.0");
            }
            while (true) {
                String scope = dis.readUTF();
                int freq = dis.readInt();
                int len = dis.readInt();
                byte[] key = new byte[len]; dis.readFully(key);
                long count = dis.readLong();
                AEItemKey aek = null;
                try { ItemStack s = deserializeItemStackFromBytes(key);
                    if (!s.isEmpty()) aek = AEItemKey.of(s);
                } catch (Exception x) { x.printStackTrace(); }
                dbMap.put(new AEKey(scope, freq, key), new StoredEntry(count, aek));
            }
        } catch (EOFException ignored) {}
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
        if (DEBUG_LOG) LOGGER.info("[EnderDBManager] " + format, args);
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

        log("[migrateOldRecords] Detected {} old-format records. Migrating to global scope...", toMigrate.size());

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

    private static void backupDatabaseFile(String version) {
        String timestamp = java.time.LocalDateTime.now()
                .toString()
                .replace(":", "-");

        String backupName = String.format("enderdrives_%s_%s.zip", version, timestamp);
        File backupZip = new File(dbFile.getParent(), backupName);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupZip))) {
            zipFile(dbFile, zos);
            if (currentWAL != null && currentWAL.exists()) {
                zipFile(currentWAL, zos);
            }
            LOGGER.info("Backed up existing database to {} due to mod version change.", backupZip.getName());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void zipFile(File file, ZipOutputStream zos) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            ZipEntry entry = new ZipEntry(file.getName());
            zos.putNextEntry(entry);
            fis.transferTo(zos);
            zos.closeEntry();
        }
    }

}