package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import static com.sts15.enderdrives.inventory.EnderDiskInventory.deserializeItemStackFromBytes;

public class EnderDBManager_functional {
    private static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();
    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    // Using a volatile reference for the delta buffer so we can swap it atomically.
    private static volatile ConcurrentHashMap<AEKey, Long> deltaBuffer = new ConcurrentHashMap<>();
    // Lock for synchronizing deltaBuffer swaps.
    private static final Object deltaLock = new Object();
    private static final ConcurrentHashMap<String, CachedCount> itemCountCache = new ConcurrentHashMap<>();
    private static final int MERGE_BUFFER_THRESHOLD = 1000;
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    private static volatile boolean running = true, dirty = false;
    private static final long MIN_COMMIT_INTERVAL_MS = 2500, MAX_COMMIT_INTERVAL_MS = 60000;
    private static long lastCommitTime = System.currentTimeMillis();
    private static long lastDbCommitTime = System.currentTimeMillis();
    private static final AtomicLong totalItemsWritten = new AtomicLong(0);
    private static final AtomicLong totalCommits = new AtomicLong(0);
    private static final long MIN_DB_COMMIT_INTERVAL_MS = 5000;
    private static final long MAX_DB_COMMIT_INTERVAL_MS = 60000;
    // Enable verbose logging for debugging.
    private static final boolean DEBUG_LOG = false;

    // Helper: logs a summary of the current dbMap state.
    private static void logDbMapState(String methodName, String when) {
        System.out.printf("[EnderDB] %s %s: dbMap has %d records%n", methodName, when, dbMap.size());
        int i = 0;
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            if (i++ >= 10) break;
            System.out.printf("[EnderDB]   %s -> %s%n", entry.getKey(), entry.getValue());
        }
    }

    // Helper method to log the current file size of the .bin file.
    private static void logBinFileSize(String context) {
        if (dbFile != null && dbFile.exists()) {
            System.out.printf("[EnderDB] %s: .bin file size is %d bytes%n", context, dbFile.length());
        } else {
            System.out.printf("[EnderDB] %s: .bin file does not exist or is null%n", context);
        }
    }

    // Helper: converts byte array to hex string.
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==== Public API ====

    public static void init() {
        System.out.println("[EnderDB] init() called");
        logDbMapState("init", "before");

        try {
            // Step 1: Set up directories and file references.
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT).resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);
            dbFile = worldDir.resolve("enderdrives.bin").toFile();
            currentWAL = worldDir.resolve("enderdrives.wal").toFile();
            logBinFileSize("After creating directories and setting file references");

            // Step 2: Migrate old records (if any)
            migrateOldRecords();
            logBinFileSize("After migrateOldRecords()");

            // Step 3: Open WAL stream.
            openWALStream();
            logBinFileSize("After openWALStream()");

            // Step 4: Replay WAL(s) to apply any pending changes.
            replayWALs();
            logBinFileSize("After replayWALs()");

            // Step 5: Load the database from disk.
            loadDatabase();
            logBinFileSize("After loadDatabase()");

            // Log loaded records.
            System.out.printf("[EnderDB] Loaded %d records into dbMap%n", dbMap.size());
            logAllLoadedItems();

            // Step 6: Start background commit thread.
            startBackgroundCommit();
            logBinFileSize("After startBackgroundCommit()");

            logDbMapState("init", "after");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[EnderDB] Shutdown gracefully.");
                shutdown();
            }));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static void shutdown() {
        System.out.println("[EnderDB] shutdown() called");
        logDbMapState("shutdown", "before");
        running = false;
        try {
            synchronized (commitLock) {
                flushDeltaBuffer();
                commitDatabase();
                truncateCurrentWAL();
                closeWALStream();
            }
        } catch (IOException ignored) {}
        logDbMapState("shutdown", "after");
    }

    public static void saveItem(String scopePrefix, int freq, byte[] itemNbtBinary, long deltaCount) {
        System.out.printf("[EnderDB] saveItem() called: scope=%s, freq=%d, delta=%d%n", scopePrefix, freq, deltaCount);
        logDbMapState("saveItem", "before");
        AEKey key = new AEKey(scopePrefix, freq, itemNbtBinary);
        deltaBuffer.merge(key, deltaCount, Long::sum);
        if (deltaBuffer.size() >= MERGE_BUFFER_THRESHOLD) {
            flushDeltaBuffer();
        }
        logDbMapState("saveItem", "after");
    }

    public static long getItemCount(String scopePrefix, int freq, byte[] itemNbtBinary) {
        System.out.printf("[EnderDB] getItemCount() called: scope=%s, freq=%d%n", scopePrefix, freq);
        AEKey key = new AEKey(scopePrefix, freq, itemNbtBinary);
        long count = dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
        System.out.printf("[EnderDB] getItemCount() returning %d%n", count);
        return count;
    }

    public static void clearFrequency(String scopePrefix, int frequency) {
        System.out.printf("[EnderDB] clearFrequency() called: scope=%s, freq=%d%n", scopePrefix, frequency);
        logDbMapState("clearFrequency", "before");
        AEKey from = new AEKey(scopePrefix, frequency, new byte[0]);
        AEKey to = new AEKey(scopePrefix, frequency + 1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> sub = dbMap.subMap(from, true, to, false);
        int removed = sub.size();
        sub.clear();
        log("Cleared frequency %d for scope %s (%d entries)", frequency, scopePrefix, removed);
        logDbMapState("clearFrequency", "after");
    }

    public static int getTypeCount(String scopePrefix, int freq) {
        System.out.printf("[EnderDB] getTypeCount() called: scope=%s, freq=%d%n", scopePrefix, freq);
        AEKey from = new AEKey(scopePrefix, freq, new byte[0]);
        AEKey to = new AEKey(scopePrefix, freq + 1, new byte[0]);
        int count = dbMap.subMap(from, true, to, false).size();
        System.out.printf("[EnderDB] getTypeCount() returning %d%n", count);
        return count;
    }

    public static List<AEKeyCacheEntry> queryItemsByFrequency(String scopePrefix, int freq) {
        System.out.printf("[EnderDB] queryItemsByFrequency() called: scope=%s, freq=%d%n", scopePrefix, freq);
        logDbMapState("queryItemsByFrequency", "before");
        List<AEKeyCacheEntry> result = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            if (key.scope().equals(scopePrefix) && key.freq() == freq) {
                StoredEntry stored = entry.getValue();
                result.add(new AEKeyCacheEntry(key, stored.aeKey(), stored.count()));
            }
        }
        log("QueryItemsByFrequency: scope=%s freq=%d entriesFound=%d", scopePrefix, freq, result.size());
        logDbMapState("queryItemsByFrequency", "after");
        return result;
    }

    public static long getTotalItemCount(String scopePrefix, int frequency) {
        System.out.printf("[EnderDB] getTotalItemCount() called: scope=%s, freq=%d%n", scopePrefix, frequency);
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
        System.out.printf("[EnderDB] calculateTotalItemCount() called: scope=%s, freq=%d%n", scopePrefix, frequency);
        List<AEKeyCacheEntry> entries = queryItemsByFrequency(scopePrefix, frequency);
        long total = 0L;
        for (AEKeyCacheEntry entry : entries) {
            total += entry.count();
        }
        log("Total items calculated for scope=%s freq=%d: %d items across %d types",
                scopePrefix, frequency, total, entries.size());
        return total;
    }

    public static List<ItemStack> getTopStacks(String scopePrefix, int frequency, int max) {
        System.out.printf("[EnderDB] getTopStacks() called: scope=%s, freq=%d, max=%d%n", scopePrefix, frequency, max);
        List<AEKeyCacheEntry> entries = queryItemsByFrequency(scopePrefix, frequency);
        entries.sort(Comparator.comparingLong(AEKeyCacheEntry::count).reversed());
        List<ItemStack> stacks = entries.stream()
                .limit(max)
                .map(e -> e.aeKey() != null ? e.aeKey().toStack((int) Math.min(e.count(), Integer.MAX_VALUE)) : ItemStack.EMPTY)
                .toList();
        System.out.printf("[EnderDB] getTopStacks() returning %d stacks%n", stacks.size());
        return stacks;
    }

    private record CachedCount(long count, long timestamp) {}

    public static void commitDatabase() {
        System.out.println("[EnderDB] commitDatabase() called");
        logDbMapState("commitDatabase", "before");
        try {
            System.out.printf("[EnderDB] Committing database with %d records%n", dbMap.size());
            File temp = new File(dbFile.getAbsolutePath() + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp), 1024 * 512))) {
                for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
                    AEKey key = entry.getKey();
                    long count = entry.getValue().count();
                    dos.writeUTF(key.scope());
                    dos.writeInt(key.freq());
                    dos.writeInt(key.itemBytes().length);
                    dos.write(key.itemBytes());
                    dos.writeLong(count);
                }
            }
            Files.move(temp.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            log("Database committed successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
        logDbMapState("commitDatabase", "after");
    }

    // ==== Public Getters / Stats ====

    public static AtomicLong getTotalItemsWritten() { return totalItemsWritten; }
    public static AtomicLong getTotalCommits() { return totalCommits; }
    public static int getWalQueueSize() { return walQueue.size(); }
    public static int getDatabaseSize() { return dbMap.size(); }
    public static long getDatabaseFileSizeBytes() { return dbFile.exists() ? dbFile.length() : 0; }

    // ==== Background Thread Handling ====

    private static void startBackgroundCommit() {
        System.out.println("[EnderDB] startBackgroundCommit() called");
        logDbMapState("startBackgroundCommit", "before");
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
                    boolean minIntervalElapsed = now - lastCommitTime >= MIN_COMMIT_INTERVAL_MS;
                    boolean maxIntervalElapsed = now - lastCommitTime >= MAX_COMMIT_INTERVAL_MS;
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
                                log("Committed %d WAL entries. TotalItems=%d", batch.size(), totalItemsWritten.get());
                            }
                            boolean minDbCommitElapsed = now - lastDbCommitTime >= MIN_DB_COMMIT_INTERVAL_MS;
                            boolean maxDbCommitElapsed = now - lastDbCommitTime >= MAX_DB_COMMIT_INTERVAL_MS;
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
        logDbMapState("startBackgroundCommit", "after");
    }

    // ==== WAL Handling & Processing ====

    private static void flushDeltaBuffer() {
        System.out.println("[EnderDB] flushDeltaBuffer() called");
        logDbMapState("flushDeltaBuffer", "before");
        Map<AEKey, Long> bufferToFlush;
        synchronized (deltaLock) {
            if (deltaBuffer.isEmpty()) {
                return;
            }
            bufferToFlush = deltaBuffer;
            deltaBuffer = new ConcurrentHashMap<>();
        }
        for (Map.Entry<AEKey, Long> entry : bufferToFlush.entrySet()) {
            AEKey key = entry.getKey();
            long delta = entry.getValue();
            if (delta == 0) continue;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeUTF(key.scope());
                dos.writeInt(key.freq());
                dos.writeInt(key.itemBytes().length);
                dos.write(key.itemBytes());
                dos.writeLong(delta);
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
            byte[] walEntry = baos.toByteArray();
            walQueue.add(walEntry);
            applyBinaryOperation(walEntry);
        }
        logDbMapState("flushDeltaBuffer", "after");
    }

    private static void applyBinaryOperation(byte[] data) {
        System.out.println("[EnderDB] applyBinaryOperation() called");
        logBinFileSize("Before applyBinaryOperation");
        logDbMapState("applyBinaryOperation", "before");
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
                log("Removed record for key=%s because newVal <= 0", key);
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
                dbMap.put(key, new StoredEntry(newVal, aeKey));
            }
            log("Applying WAL: key=%s delta=%d old=%d new=%d", key, delta, oldVal, newVal);
            dirty = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        logDbMapState("applyBinaryOperation", "after");
        logBinFileSize("After applyBinaryOperation");
    }

    private static void truncateCurrentWAL() {
        System.out.println("[EnderDB] truncateCurrentWAL() called");
        logDbMapState("truncateCurrentWAL", "before");
        logBinFileSize("Before truncateCurrentWAL");
        try {
            closeWALStream();
            new FileOutputStream(currentWAL).close();
            openWALStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        logBinFileSize("After truncateCurrentWAL");
        logDbMapState("truncateCurrentWAL", "after");
    }

    private static void replayWALs() throws IOException {
        System.out.println("[EnderDB] replayWALs() called");
        logDbMapState("replayWALs", "before");
        logBinFileSize("replayWALs() start");

        if (currentWAL.exists()) {
            logBinFileSize("Before replayAndDeleteWAL(currentWAL)");
            replayAndDeleteWAL(currentWAL);
            logBinFileSize("After replayAndDeleteWAL(currentWAL)");
        }

        File dir = currentWAL.getParentFile();
        File[] rotatedWALs = dir.listFiles((d, name) ->
                name.startsWith("enderdrives.wal.") && name.matches(".*\\.\\d+$"));
        if (rotatedWALs != null) {
            Arrays.sort(rotatedWALs);
            for (File rotated : rotatedWALs) {
                logBinFileSize("Before replayAndDeleteWAL(" + rotated.getName() + ")");
                replayAndDeleteWAL(rotated);
                logBinFileSize("After replayAndDeleteWAL(" + rotated.getName() + ")");
            }
        }

        truncateCurrentWAL();
        logBinFileSize("After truncateCurrentWAL()");
        logDbMapState("replayWALs", "after");
    }

    private static void replayAndDeleteWAL(File walFile) {
        System.out.printf("[EnderDB] replayAndDeleteWAL() called on file %s%n", walFile.getName());
        logBinFileSize("replayAndDeleteWAL(" + walFile.getName() + ") start");
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
            // Do not call commitDatabase() here; let replayWALs() call it once after processing all WALs.
            if (walFile.delete()) {
                log("Deleted WAL file %s", walFile.getName());
            } else {
                log("Failed to delete WAL file %s", walFile.getName());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        logBinFileSize("replayAndDeleteWAL(" + walFile.getName() + ") end");
    }



    // ==== File & Stream Management ====

    private static void openWALStream() throws IOException {
        System.out.println("[EnderDB] openWALStream() called");
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter = new DataOutputStream(new BufferedOutputStream(walFileStream));
    }

    private static void closeWALStream() throws IOException {
        System.out.println("[EnderDB] closeWALStream() called");
        walWriter.close();
        walFileStream.close();
    }

    private static void loadDatabase() throws IOException {
        System.out.println("[EnderDB] loadDatabase() called");
        if (!dbFile.exists()) {
            System.out.println("[EnderDB] No database file found.");
            return;
        }
        long fileLength = dbFile.length();
        System.out.printf("[EnderDB] Database file length: %d bytes%n", fileLength);
        if (fileLength == 0) {
            System.out.println("[EnderDB] Database file is empty, nothing to load.");
            return;
        }
        logDbMapState("loadDatabase", "before");
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
                    System.out.printf("[EnderDB] Loaded record: scope=%s, freq=%d, count=%d%n", scope, freq, count);
                } catch (EOFException eof) {
                    System.out.printf("[EnderDB] Reached end of file after %d records.%n", recordCount);
                    break;
                } catch (Exception ex) {
                    System.err.printf("[EnderDB] Exception while reading record: %s%n", ex);
                    break;
                }
            }
        }
        System.out.printf("[EnderDB] loadDatabase: Loaded %d records from file into dbMap%n", recordCount);
        logDbMapState("loadDatabase", "after");
    }



    // ==== Logging Helper ====

    private static void logAllLoadedItems() {
        System.out.println("[EnderDB] Loaded database entries:");
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            StoredEntry stored = entry.getValue();
            System.out.printf("Scope: %s, Frequency: %d, Count: %d, KeyBytes: %s%n",
                    key.scope(), key.freq(), stored.count(),
                    Arrays.toString(Arrays.copyOf(key.itemBytes(), Math.min(10, key.itemBytes().length))));
        }
    }

    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void log(String format, Object... args) {
        if (DEBUG_LOG) {
            System.out.printf("[EnderDB] " + format + "%n", args);
        }
    }

    private static void migrateOldRecords() {
        System.out.println("[EnderDB] migrateOldRecords() called");
        logDbMapState("migrateOldRecords", "before");
        List<Map.Entry<AEKey, StoredEntry>> toMigrate = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            String scope = key.scope();
            if (scope == null || scope.isEmpty() ||
                    (scope.length() > 0 && !scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global"))) {
                toMigrate.add(entry);
            }
        }
        if (toMigrate.isEmpty()) {
            log("No records to migrate.");
            return;
        }
        System.out.printf("[EnderDB] Detected %d old-format records. Migrating to global scope...%n", toMigrate.size());
        for (Map.Entry<AEKey, StoredEntry> entry : toMigrate) {
            AEKey oldKey = entry.getKey();
            StoredEntry value = entry.getValue();
            AEKey newKey = new AEKey("global", oldKey.freq(), oldKey.itemBytes());
            long existing = dbMap.getOrDefault(newKey, new StoredEntry(0L, null)).count();
            dbMap.put(newKey, new StoredEntry(existing + value.count(), null));
            dbMap.remove(oldKey);
            log("Migrated record from %s to %s", oldKey, newKey);
        }
        System.out.printf("[EnderDB] Migration complete. Migrated %d entries.%n", toMigrate.size());
        dirty = true;
        logDbMapState("migrateOldRecords", "after");
    }
}
