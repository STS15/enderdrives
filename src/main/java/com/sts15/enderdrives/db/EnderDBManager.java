package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.config.serverConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.sts15.enderdrives.inventory.EnderDiskInventory.deserializeItemStackFromBytes;

public final class EnderDBManager {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    private static final String SNAPSHOT_MAGIC = "EDB2";
    private static final String LEGACY_SNAPSHOT_MAGIC = "EDB1";
    private static final int SNAPSHOT_FORMAT = 2;
    private static final int WAL_TRANSACTION_MAGIC = 0x45445732; // EDW2
    private static final int MAX_KEY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_WAL_RECORD_BYTES = 64 * 1024 * 1024;
    private static final int MAX_WAL_TRANSACTION_OPERATIONS = 100_000;
    private static final int MAX_SNAPSHOT_ENTRIES = 10_000_000;
    private static final int MAX_WAL_MARKERS = 10_000;
    private static final String WAL_FILE_NAME = "enderdrives.wal";
    private static final String ROTATED_WAL_PREFIX = WAL_FILE_NAME + ".";

    public static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();

    private static final ConcurrentHashMap<String, CachedCount> itemCountCache = new ConcurrentHashMap<>();
    private static final Object commitLock = new Object();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();
    private static final AtomicLong totalItemsWritten = new AtomicLong();
    private static final AtomicLong totalCommits = new AtomicLong();
    private static File dbFile;
    private static File currentWAL;
    private static FileOutputStream walFileStream;
    private static Thread commitThread;
    private static volatile boolean initialized;
    private static volatile Lifecycle lifecycle = Lifecycle.STOPPED;
    private static Map<String, WalCheckpoint> loadedWalCheckpoints = Map.of();
    private static volatile long minDbCommitMs = 1_000L;
    private static volatile boolean debugLog;
    public static volatile boolean running;
    public static volatile boolean dirty;

    private EnderDBManager() {}

    // ==== Public API ====

    public static synchronized void init() {
        if (isReady() && commitThread != null && commitThread.isAlive()) return;

        stopBackgroundThread();
        synchronized (commitLock) {
            lifecycle = Lifecycle.STARTING;
            running = false;
            initialized = false;
            closeWalQuietly();
            dbMap.clear();
            itemCountCache.clear();
            loadedWalCheckpoints = Map.of();
            dirty = false;

            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) throw new IOException("Cannot initialize item storage without a running server");

                Path worldDir = server.getWorldPath(LevelResource.ROOT)
                        .resolve("data")
                        .resolve("enderdrives");
                Files.createDirectories(worldDir);
                dbFile = worldDir.resolve("enderdrives.bin").toFile();
                currentWAL = worldDir.resolve(WAL_FILE_NAME).toFile();
                minDbCommitMs = Math.max(100L, serverConfig.END_DB_MIN_DB_COMMIT_INTERVAL_MS.get());
                debugLog = serverConfig.END_DB_DEBUG_LOG.get();

                loadDatabase();
                replayWALs();
                migrateOldRecords();
                openWALStream();

                // Recovery and legacy migrations must reach a snapshot before cells can mutate state.
                if (dirty && !commitDatabaseLocked()) {
                    throw new IOException("Could not checkpoint recovered item storage");
                }

                initialized = true;
                running = true;
                lifecycle = Lifecycle.RUNNING;
                startBackgroundCommit();

                if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
                    Runtime.getRuntime().addShutdownHook(
                            new Thread(EnderDBManager::shutdown, "EnderDrives-ItemDB-Shutdown"));
                }
            } catch (Exception e) {
                running = false;
                initialized = false;
                lifecycle = Lifecycle.FAILED;
                closeWalQuietly();
                LOGGER.error("Failed to initialize the item database; storage mutations are disabled", e);
            }
        }
    }

    public static synchronized void shutdown() {
        synchronized (commitLock) {
            if (lifecycle == Lifecycle.STOPPED && !initialized) return;
            lifecycle = Lifecycle.STOPPING;
            running = false;
        }

        stopBackgroundThread();

        synchronized (commitLock) {
            boolean committed = dbFile != null && currentWAL != null && walFileStream != null
                    && commitDatabaseLocked();
            closeWalQuietly();
            initialized = false;

            if (committed) {
                dbMap.clear();
                itemCountCache.clear();
                dirty = false;
                lifecycle = Lifecycle.STOPPED;
            } else {
                // Keep the in-memory image intact for diagnostics or an in-process retry.
                lifecycle = Lifecycle.FAILED;
                LOGGER.error("Final item database checkpoint failed; retained the in-memory database");
            }
        }
    }

    public static boolean isReady() {
        return initialized && running && lifecycle == Lifecycle.RUNNING;
    }

    /** Clears derived caches without discarding the live database image. */
    public static void clearRAMCaches() {
        itemCountCache.clear();
        log("[clearRAMCaches] Derived item caches cleared");
    }

    /**
     * Applies an unrestricted signed item delta and returns the exact signed delta accepted.
     * Positive deltas saturate at {@link Long#MAX_VALUE}; negative deltas stop at zero.
     */
    public static long saveItem(String scopePrefix, int freq, byte[] itemBytes, long deltaCount) {
        return mutateItem(scopePrefix, freq, itemBytes, deltaCount, null);
    }

    /**
     * Atomically checks an inventory type limit, inserts an item, and returns the amount accepted.
     */
    public static long insertItem(
            String scopePrefix,
            int freq,
            byte[] itemBytes,
            long requestedAmount,
            int typeLimit
    ) {
        if (requestedAmount <= 0) return 0L;
        return mutateItem(scopePrefix, freq, itemBytes, requestedAmount, Math.max(0, typeLimit));
    }

    private static long mutateItem(
            String scopePrefix,
            int freq,
            byte[] itemBytes,
            long requestedDelta,
            Integer typeLimit
    ) {
        if (requestedDelta == 0 || !validKey(scopePrefix, itemBytes)) return 0L;

        synchronized (commitLock) {
            if (!canMutateLocked()) return 0L;

            AEKey canonicalKey = new AEKey(scopePrefix, freq, itemBytes.clone());
            AEItemKey requestedAeKey = deserializeAeKey(itemBytes);
            SemanticMatch match = findSemanticMatchLocked(canonicalKey, requestedAeKey);
            long current = match.total();
            if (match.overflowed()) {
                LOGGER.error("Refusing to mutate an item whose legacy semantic aliases exceed Long.MAX_VALUE");
                return 0L;
            }

            if (requestedDelta > 0 && typeLimit != null && current == 0
                    && frequencySizeLocked(scopePrefix, freq) >= typeLimit) {
                return 0L;
            }

            long accepted = clampDelta(current, requestedDelta);
            if (accepted == 0 && match.alternateEntries().isEmpty()) return 0L;

            long targetCount = applyClampedDelta(current, accepted);
            List<byte[]> walRecords;
            try {
                walRecords = createRewriteRecords(canonicalKey, match, targetCount);
            } catch (IOException e) {
                LOGGER.error("Could not serialize an item WAL transaction", e);
                return 0L;
            }

            if (!appendWalRecordsLocked(walRecords)) return 0L;

            for (Map.Entry<AEKey, StoredEntry> alternate : match.alternateEntries()) {
                dbMap.remove(alternate.getKey());
            }
            if (targetCount == 0) {
                dbMap.remove(canonicalKey);
            } else {
                dbMap.put(canonicalKey, new StoredEntry(targetCount, requestedAeKey));
            }

            invalidateFrequencyCache(scopePrefix, freq);
            dirty = true;
            return accepted;
        }
    }

    public static long getItemCount(String scopePrefix, int freq, byte[] keyBytes) {
        if (!validKey(scopePrefix, keyBytes)) return 0L;

        AEKey canonicalKey = new AEKey(scopePrefix, freq, keyBytes.clone());
        synchronized (commitLock) {
            StoredEntry direct = dbMap.get(canonicalKey);
            AEItemKey requestedAeKey = deserializeAeKey(keyBytes);
            if (requestedAeKey == null) return direct == null ? 0L : direct.count();

            SemanticMatch match = findSemanticMatchLocked(canonicalKey, requestedAeKey);
            long total = match.total();
            if (total == 0 || match.overflowed()
                    || match.alternateEntries().isEmpty() || !canMutateLocked()) return total;

            try {
                List<byte[]> records = createRewriteRecords(canonicalKey, match, total);
                if (!appendWalRecordsLocked(records)) return total;
            } catch (IOException e) {
                LOGGER.error("Could not persist semantic item-key migration", e);
                return total;
            }

            for (Map.Entry<AEKey, StoredEntry> alternate : match.alternateEntries()) {
                dbMap.remove(alternate.getKey());
            }
            dbMap.put(canonicalKey, new StoredEntry(total, requestedAeKey));
            invalidateFrequencyCache(scopePrefix, freq);
            dirty = true;
            return total;
        }
    }

    /**
     * Durably moves every item key in one scope to another scope. Existing destination counts
     * saturate at {@link Long#MAX_VALUE}; the source scope is removed only after the WAL transaction
     * has been forced to disk.
     */
    public static boolean migrateScope(String oldScope, String newScope) {
        if (oldScope == null || oldScope.isEmpty() || newScope == null || newScope.isEmpty()) return false;
        if (oldScope.equals(newScope)) return true;

        synchronized (commitLock) {
            if (!canMutateLocked()) return false;

            List<Map.Entry<AEKey, StoredEntry>> sources = scopeEntriesLocked(oldScope);
            if (sources.isEmpty()) return true;
            if (sources.size() > MAX_WAL_TRANSACTION_OPERATIONS / 2) {
                LOGGER.error("Refusing item scope migration with too many entries: {}", sources.size());
                return false;
            }

            List<ScopeMigration> migrations = new ArrayList<>(sources.size());
            List<byte[]> walRecords = new ArrayList<>(sources.size() * 2);
            try {
                for (Map.Entry<AEKey, StoredEntry> source : sources) {
                    AEKey sourceKey = source.getKey();
                    AEKey destinationKey = new AEKey(
                            newScope, sourceKey.freq(), sourceKey.itemBytes().clone());
                    StoredEntry destination = dbMap.get(destinationKey);
                    long destinationCount = destination == null ? 0L : destination.count();
                    long mergedCount = saturatedAdd(destinationCount, source.getValue().count());

                    walRecords.add(createWalRecord(
                            sourceKey.scope(), sourceKey.freq(), sourceKey.itemBytes(), -source.getValue().count()));
                    long destinationDelta = mergedCount - destinationCount;
                    if (destinationDelta != 0) {
                        walRecords.add(createWalRecord(
                                destinationKey.scope(), destinationKey.freq(),
                                destinationKey.itemBytes(), destinationDelta));
                    }
                    migrations.add(new ScopeMigration(sourceKey, destinationKey, mergedCount));
                }
            } catch (IOException e) {
                LOGGER.error("Could not serialize item scope migration {} -> {}", oldScope, newScope, e);
                return false;
            }

            if (!appendWalRecordsLocked(walRecords)) return false;
            for (ScopeMigration migration : migrations) {
                StoredEntry source = dbMap.remove(migration.source());
                StoredEntry destination = dbMap.get(migration.destination());
                AEItemKey aeKey = destination != null && destination.aeKey() != null
                        ? destination.aeKey()
                        : source == null ? null : source.aeKey();
                dbMap.put(migration.destination(), new StoredEntry(migration.mergedCount(), aeKey));
                invalidateFrequencyCache(oldScope, migration.source().freq());
                invalidateFrequencyCache(newScope, migration.destination().freq());
            }
            dirty = true;
            return true;
        }
    }

    public static int getTypeCountInclusive(String scope, int freq) {
        return getTypeCount(scope, freq);
    }

    public static long getTotalItemCountInclusive(String scope, int freq) {
        synchronized (commitLock) {
            long total = 0L;
            for (Map.Entry<AEKey, StoredEntry> entry : frequencyEntriesLocked(scope, freq)) {
                total = saturatedAdd(total, entry.getValue().count());
            }
            return total;
        }
    }

    public static void clearFrequency(String scopePrefix, int frequency) {
        if (scopePrefix == null) return;
        synchronized (commitLock) {
            if (!canMutateLocked()) return;
            List<Map.Entry<AEKey, StoredEntry>> entries = frequencyEntriesLocked(scopePrefix, frequency);
            if (entries.isEmpty()) return;

            List<byte[]> records = new ArrayList<>(entries.size());
            try {
                for (Map.Entry<AEKey, StoredEntry> entry : entries) {
                    records.add(createWalRecord(
                            scopePrefix,
                            frequency,
                            entry.getKey().itemBytes(),
                            -entry.getValue().count()));
                }
            } catch (IOException e) {
                LOGGER.error("Could not serialize the item-frequency clear transaction", e);
                return;
            }

            if (!appendWalRecordsLocked(records)) return;
            for (Map.Entry<AEKey, StoredEntry> entry : entries) dbMap.remove(entry.getKey());
            invalidateFrequencyCache(scopePrefix, frequency);
            dirty = true;
            log("[clearFrequency] Cleared frequency {} for scope {} ({} entries)",
                    frequency, scopePrefix, entries.size());
        }
    }

    public static int getTypeCount(String scopePrefix, int freq) {
        if (scopePrefix == null) return 0;
        synchronized (commitLock) {
            return frequencySizeLocked(scopePrefix, freq);
        }
    }

    public static List<AEKeyCacheEntry> queryItemsByFrequency(String scopePrefix, int freq) {
        if (scopePrefix == null) return List.of();
        synchronized (commitLock) {
            List<AEKeyCacheEntry> result = new ArrayList<>();
            for (Map.Entry<AEKey, StoredEntry> entry : frequencyEntriesLocked(scopePrefix, freq)) {
                StoredEntry stored = entry.getValue();
                if (stored.count() <= 0) continue;

                AEItemKey aeKey = stored.aeKey();
                if (aeKey == null) {
                    aeKey = deserializeAeKey(entry.getKey().itemBytes());
                    if (aeKey != null) {
                        dbMap.put(entry.getKey(), new StoredEntry(stored.count(), aeKey));
                    }
                }
                if (aeKey != null) result.add(new AEKeyCacheEntry(entry.getKey(), aeKey, stored.count()));
            }
            return result;
        }
    }

    public static long getTotalItemCount(String scopePrefix, int frequency) {
        String cacheKey = scopePrefix + "|" + frequency;
        CachedCount cached = itemCountCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestamp() < 1_000) return cached.count();

        long total = getTotalItemCountInclusive(scopePrefix, frequency);
        itemCountCache.put(cacheKey, new CachedCount(total, now));
        return total;
    }

    public static List<ItemStack> getTopStacks(String scopePrefix, int frequency, int max) {
        if (max <= 0) return List.of();
        return queryItemsByFrequency(scopePrefix, frequency).stream()
                .sorted(Comparator.comparingLong(AEKeyCacheEntry::count).reversed())
                .limit(max)
                .map(entry -> entry.aeKey().toStack((int) Math.min(entry.count(), Integer.MAX_VALUE)))
                .toList();
    }

    /**
     * Checkpoints the database. A true result means the snapshot and its WAL recovery marker are durable.
     */
    public static boolean commitDatabase() {
        synchronized (commitLock) {
            return commitDatabaseLocked();
        }
    }

    private static boolean commitDatabaseLocked() {
        if (dbFile == null || currentWAL == null || walFileStream == null) return false;

        try {
            forceCurrentWalLocked();
            List<WalCheckpoint> checkpoints = collectWalCheckpointsLocked();
            if (!writeDatabaseSnapshotLocked(checkpoints)) return false;

            boolean removedRotated = deleteCheckpointedRotatedWals(checkpoints);
            if (removedRotated) {
                if (!truncateCurrentWALLocked()) return false;

                // Clear the old-generation marker before accepting a new WAL generation. Without
                // this second atomic replace, an identical first transaction could match length+CRC.
                if (!writeDatabaseSnapshotLocked(List.of())) {
                    failLifecycleLocked("Could not clear the item checkpoint WAL marker");
                    return false;
                }
                loadedWalCheckpoints = Map.of();
            } else {
                loadedWalCheckpoints = checkpointMap(checkpoints);
            }

            dirty = false;
            log("[commitDatabase] Item database checkpointed successfully");
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to checkpoint the item database", e);
            return false;
        }
    }

    private static boolean writeDatabaseSnapshotLocked(List<WalCheckpoint> checkpoints) {
        if (checkpoints.size() > MAX_WAL_MARKERS || dbMap.size() > MAX_SNAPSHOT_ENTRIES) {
            LOGGER.error("Refusing oversized item snapshot: markers={} entries={}",
                    checkpoints.size(), dbMap.size());
            return false;
        }
        File temp = new File(dbFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream buffered = new BufferedOutputStream(fileOut, 512 * 1024);
             DataOutputStream out = new DataOutputStream(buffered)) {
            out.writeUTF(SNAPSHOT_MAGIC);
            out.writeUTF(currentModVersion());
            out.writeInt(SNAPSHOT_FORMAT);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(checkpoints.size());
            for (WalCheckpoint checkpoint : checkpoints) {
                out.writeUTF(checkpoint.fileName());
                out.writeLong(checkpoint.length());
                out.writeLong(checkpoint.checksum());
            }

            out.writeInt(dbMap.size());
            for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
                AEKey key = entry.getKey();
                out.writeUTF(key.scope());
                out.writeInt(key.freq());
                out.writeInt(key.itemBytes().length);
                out.write(key.itemBytes());
                out.writeLong(entry.getValue().count());
            }
            out.flush();
            fileOut.getChannel().force(true);
        } catch (IOException e) {
            LOGGER.error("Failed to write the item database snapshot", e);
            return false;
        }

        try {
            replaceDatabaseFile(temp, dbFile);
            forceDirectory(dbFile.toPath().getParent());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to install the item database snapshot", e);
            return false;
        }
    }

    // ==== Public Getters / Stats ====

    public static AtomicLong getTotalItemsWritten() { return totalItemsWritten; }
    public static AtomicLong getTotalCommits() { return totalCommits; }
    public static int getDatabaseSize() { return dbMap.size(); }
    public static long getDatabaseFileSizeBytes() {
        return dbFile != null && dbFile.exists() ? dbFile.length() : 0L;
    }

    // ==== Background Thread ====

    private static void startBackgroundCommit() {
        if (commitThread != null && commitThread.isAlive()) return;
        commitThread = new Thread(() -> {
            long nextCheckpoint = System.currentTimeMillis() + minDbCommitMs;
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (dirty && now >= nextCheckpoint) {
                        if (commitDatabase()) totalCommits.incrementAndGet();
                        nextCheckpoint = now + minDbCommitMs;
                    }
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Item database background checkpoint failed", e);
                }
            }
        }, "EnderDB-CommitThread");
        commitThread.setDaemon(true);
        commitThread.start();
    }

    private static void stopBackgroundThread() {
        running = false;
        Thread thread = commitThread;
        if (thread == null || thread == Thread.currentThread()) {
            commitThread = null;
            return;
        }
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) LOGGER.warn("Item database checkpoint thread did not stop within two seconds");
        commitThread = null;
    }

    // ==== Mutation and WAL Transactions ====

    private static boolean canMutateLocked() {
        return lifecycle == Lifecycle.RUNNING && initialized && running && walFileStream != null;
    }

    private static List<byte[]> createRewriteRecords(
            AEKey canonicalKey,
            SemanticMatch match,
            long targetCount
    ) throws IOException {
        List<byte[]> records = new ArrayList<>(match.alternateEntries().size() + 1);
        for (Map.Entry<AEKey, StoredEntry> alternate : match.alternateEntries()) {
            records.add(createWalRecord(
                    alternate.getKey().scope(),
                    alternate.getKey().freq(),
                    alternate.getKey().itemBytes(),
                    -alternate.getValue().count()));
        }

        long canonicalDelta = targetCount - match.canonicalCount();
        if (canonicalDelta != 0) {
            records.add(createWalRecord(
                    canonicalKey.scope(), canonicalKey.freq(), canonicalKey.itemBytes(), canonicalDelta));
        }
        return records;
    }

    private static boolean appendWalRecordsLocked(List<byte[]> records) {
        if (records.isEmpty()) return true;
        if (walFileStream == null) return false;

        byte[] transaction;
        try {
            transaction = createWalTransaction(records);
        } catch (IOException e) {
            LOGGER.error("Could not serialize the item WAL transaction", e);
            return false;
        }

        long startOffset;
        try {
            startOffset = walFileStream.getChannel().position();
        } catch (IOException e) {
            LOGGER.error("Could not read the item WAL append position", e);
            return false;
        }

        try {
            DataOutputStream out = new DataOutputStream(walFileStream);
            out.writeInt(transaction.length);
            out.write(transaction);
            out.writeLong(checksum(transaction));
            out.flush();
            walFileStream.getChannel().force(false);
            totalItemsWritten.addAndGet(records.size());
            return true;
        } catch (IOException e) {
            LOGGER.error("Item WAL append failed; rejecting the mutation", e);
            if (!rollbackWalAppendLocked(startOffset)) {
                failLifecycleLocked("Could not restore the item WAL after an append failure");
            }
            return false;
        }
    }

    private static boolean rollbackWalAppendLocked(long startOffset) {
        closeWalQuietly();
        try (RandomAccessFile file = new RandomAccessFile(currentWAL, "rw")) {
            file.setLength(startOffset);
            file.getFD().sync();
        } catch (IOException e) {
            LOGGER.error("Could not roll the item WAL back to offset {}", startOffset, e);
            return false;
        }

        try {
            openWALStream();
            return true;
        } catch (IOException e) {
            LOGGER.error("Could not reopen the item WAL after rollback", e);
            return false;
        }
    }

    public static void flushWALQueue() {
        synchronized (commitLock) {
            try {
                forceCurrentWalLocked();
            } catch (IOException e) {
                LOGGER.error("Could not force the item WAL", e);
            }
        }
    }

    private static void forceCurrentWalLocked() throws IOException {
        if (walFileStream == null) throw new IOException("Item WAL stream is not open");
        walFileStream.flush();
        walFileStream.getChannel().force(false);
    }

    // ==== WAL Recovery ====

    private static void replayWALs() throws IOException {
        for (File wal : walFilesInReplayOrder()) replayWalFile(wal);
    }

    private static void replayWalFile(File walFile) throws IOException {
        if (!walFile.exists() || walFile.length() == 0) return;
        long replayOffset = replayOffsetFor(walFile);

        try (RandomAccessFile file = new RandomAccessFile(walFile, "rw")) {
            if (replayOffset > file.length()) replayOffset = 0;
            file.seek(replayOffset);

            while (file.getFilePointer() < file.length()) {
                long recordStart = file.getFilePointer();
                try {
                    long remaining = file.length() - recordStart;
                    if (remaining < Integer.BYTES) throw new EOFException("Partial WAL length field");

                    int length = file.readInt();
                    if (length <= 0 || length > MAX_WAL_RECORD_BYTES) {
                        throw new IOException("Invalid WAL record length " + length);
                    }
                    if (file.length() - file.getFilePointer() < (long) length + Long.BYTES) {
                        throw new EOFException("Partial WAL record");
                    }

                    byte[] payload = new byte[length];
                    file.readFully(payload);
                    long storedChecksum = file.readLong();
                    if (checksum(payload) != storedChecksum) throw new IOException("WAL checksum mismatch");
                    applyBinaryOperation(payload);
                } catch (IOException corrupt) {
                    quarantineAndTruncateWal(file, walFile, recordStart, corrupt);
                    break;
                }
            }
        }
    }

    private static long replayOffsetFor(File walFile) throws IOException {
        WalCheckpoint checkpoint = loadedWalCheckpoints.get(walFile.getName());
        if (checkpoint == null || checkpoint.length() <= 0) return 0L;
        if (walFile.length() < checkpoint.length()) {
            if (walFile.getName().equals(WAL_FILE_NAME)) {
                // The previous checkpoint may have truncated current WAL before a crash.
                return 0L;
            }
            quarantineWalCopy(walFile, "checkpointed rotated WAL is shorter than its marker");
            return walFile.length();
        }

        long actualChecksum = checksumPrefix(walFile, checkpoint.length());
        if (actualChecksum != checkpoint.checksum()) {
            // The snapshot already contains this prefix. Replaying a damaged copy would duplicate
            // valid records, so preserve it for diagnosis and continue only after the marked offset.
            quarantineWalCopy(walFile, "checkpointed WAL prefix checksum mismatch");
        }
        return checkpoint.length();
    }

    private static void quarantineAndTruncateWal(
            RandomAccessFile activeFile,
            File walFile,
            long validLength,
            IOException cause
    ) throws IOException {
        Path quarantine;
        try {
            quarantine = quarantineWalCopy(walFile, cause.getMessage());
        } catch (IOException copyFailure) {
            cause.addSuppressed(copyFailure);
            throw new IOException("Corrupt WAL was preserved because quarantine failed: " + walFile, cause);
        }

        activeFile.setLength(validLength);
        activeFile.getFD().sync();
        LOGGER.error("Quarantined corrupt item WAL tail to {} and retained {} valid bytes",
                quarantine.getFileName(), validLength, cause);
        dirty = true;
    }

    private static Path quarantineWalCopy(File walFile, String reason) throws IOException {
        Path quarantine = uniqueQuarantinePath(walFile.toPath());
        Files.copy(walFile.toPath(), quarantine);
        dirty = true;
        LOGGER.error("Preserved item WAL {} as {}: {}",
                walFile.getName(), quarantine.getFileName(), reason);
        return quarantine;
    }

    private static Path uniqueQuarantinePath(Path source) {
        String base = source.getFileName() + ".corrupt." + System.currentTimeMillis();
        Path candidate = source.resolveSibling(base);
        int suffix = 0;
        while (Files.exists(candidate)) candidate = source.resolveSibling(base + "." + (++suffix));
        return candidate;
    }

    private static void applyBinaryOperation(byte[] payload) throws IOException {
        List<WalOperation> operations = parseWalOperations(payload);
        for (WalOperation operation : operations) {
            AEKey key = new AEKey(operation.scope(), operation.freq(), operation.itemBytes());
            long oldCount = dbMap.getOrDefault(key, StoredEntry.EMPTY).count();
            long newCount = applyClampedDelta(oldCount, clampDelta(oldCount, operation.delta()));
            if (newCount == 0) {
                dbMap.remove(key);
            } else {
                dbMap.put(key, new StoredEntry(newCount, deserializeAeKey(operation.itemBytes())));
            }
            invalidateFrequencyCache(operation.scope(), operation.freq());
        }
        dirty = !operations.isEmpty() || dirty;
    }

    private static List<WalOperation> parseWalOperations(byte[] payload) throws IOException {
        if (payload.length >= Integer.BYTES * 2) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (in.readInt() == WAL_TRANSACTION_MAGIC) {
                    int operationCount = in.readInt();
                    if (operationCount <= 0 || operationCount > MAX_WAL_TRANSACTION_OPERATIONS) {
                        throw new IOException("Invalid EDW2 operation count " + operationCount);
                    }

                    List<WalOperation> operations = new ArrayList<>(operationCount);
                    for (int i = 0; i < operationCount; i++) {
                        int operationLength = in.readInt();
                        if (operationLength <= 0 || operationLength > MAX_WAL_RECORD_BYTES) {
                            throw new IOException("Invalid EDW2 operation length " + operationLength);
                        }
                        byte[] operation = new byte[operationLength];
                        in.readFully(operation);
                        operations.add(parseLegacyWalOperation(operation));
                    }
                    if (in.read() != -1) throw new IOException("Trailing bytes in EDW2 transaction");
                    return List.copyOf(operations);
                }
            }
        }
        return List.of(parseLegacyWalOperation(payload));
    }

    private static WalOperation parseLegacyWalOperation(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            String scope = in.readUTF();
            int freq = in.readInt();
            int keyLength = in.readInt();
            validateKeyLength(keyLength);
            byte[] keyBytes = new byte[keyLength];
            in.readFully(keyBytes);
            long delta = in.readLong();
            if (in.read() != -1) throw new IOException("Trailing bytes in item WAL operation");
            if (!validKey(scope, keyBytes)) throw new IOException("Invalid item WAL key");
            return new WalOperation(scope, freq, keyBytes, delta);
        }
    }

    private static List<WalCheckpoint> collectWalCheckpointsLocked() throws IOException {
        List<WalCheckpoint> checkpoints = new ArrayList<>();
        for (File wal : walFilesInReplayOrder()) {
            long length = wal.length();
            if (length > 0) checkpoints.add(new WalCheckpoint(wal.getName(), length, checksumPrefix(wal, length)));
        }
        return checkpoints;
    }

    private static boolean deleteCheckpointedRotatedWals(List<WalCheckpoint> checkpoints) {
        boolean allDeleted = true;
        File directory = currentWAL.getParentFile();
        for (WalCheckpoint checkpoint : checkpoints) {
            if (checkpoint.fileName().equals(currentWAL.getName())) continue;
            File rotated = new File(directory, checkpoint.fileName());
            if (rotated.exists() && !rotated.delete()) {
                allDeleted = false;
                LOGGER.warn("Could not delete checkpointed rotated item WAL {}", rotated.getName());
            }
        }
        return allDeleted;
    }

    private static boolean truncateCurrentWALLocked() {
        closeWalQuietly();
        try (RandomAccessFile file = new RandomAccessFile(currentWAL, "rw")) {
            file.setLength(0L);
            file.getFD().sync();
        } catch (IOException e) {
            LOGGER.error("Could not truncate the checkpointed item WAL", e);
            failLifecycleLocked("Item WAL truncation failed");
            return false;
        }

        try {
            openWALStream();
            return true;
        } catch (IOException e) {
            LOGGER.error("Could not reopen the item WAL after checkpoint", e);
            failLifecycleLocked("Item WAL reopen failed");
            return false;
        }
    }

    private static List<File> walFilesInReplayOrder() {
        List<File> files = new ArrayList<>();
        if (currentWAL == null) return files;

        File directory = currentWAL.getParentFile();
        File[] rotated = directory.listFiles((dir, name) ->
                name.startsWith(ROTATED_WAL_PREFIX)
                        && name.substring(ROTATED_WAL_PREFIX.length()).matches("\\d+"));
        if (rotated != null) {
            Arrays.sort(rotated, Comparator.comparingLong(EnderDBManager::rotatedWalIndex));
            files.addAll(Arrays.asList(rotated));
        }
        if (currentWAL.exists()) files.add(currentWAL);
        return files;
    }

    private static long rotatedWalIndex(File file) {
        try {
            return Long.parseLong(file.getName().substring(ROTATED_WAL_PREFIX.length()));
        } catch (RuntimeException ignored) {
            return Long.MAX_VALUE;
        }
    }

    // ==== Snapshot Loading ====

    private static void loadDatabase() throws IOException {
        if (!dbFile.exists() || dbFile.length() == 0) return;

        try (BufferedInputStream buffered = new BufferedInputStream(new FileInputStream(dbFile))) {
            buffered.mark(128);
            DataInputStream in = new DataInputStream(buffered);
            String magic;
            try {
                magic = in.readUTF();
            } catch (UTFDataFormatException e) {
                buffered.reset();
                loadLegacyHeaderlessDatabase(in);
                dirty = true;
                return;
            }

            if (SNAPSHOT_MAGIC.equals(magic)) {
                loadEdb2(in);
            } else if (LEGACY_SNAPSHOT_MAGIC.equals(magic)) {
                loadEdb1(in);
                dirty = true;
            } else {
                buffered.reset();
                backupDatabaseFile("0.0.0");
                loadLegacyHeaderlessDatabase(in);
                dirty = true;
            }
        }
    }

    private static void loadEdb2(DataInputStream in) throws IOException {
        String fileVersion = in.readUTF();
        int format = in.readInt();
        long timestamp = in.readLong();
        if (format != SNAPSHOT_FORMAT) throw new IOException("Unsupported EDB2 format " + format);

        int markerCount = in.readInt();
        if (markerCount < 0 || markerCount > MAX_WAL_MARKERS) {
            throw new IOException("Invalid EDB2 WAL marker count " + markerCount);
        }
        Map<String, WalCheckpoint> checkpoints = new HashMap<>();
        for (int i = 0; i < markerCount; i++) {
            String fileName = in.readUTF();
            long length = in.readLong();
            long crc = in.readLong();
            if (!isSafeWalMarker(fileName, length)) throw new IOException("Invalid EDB2 WAL marker");
            checkpoints.put(fileName, new WalCheckpoint(fileName, length, crc));
        }
        loadedWalCheckpoints = Map.copyOf(checkpoints);

        int entryCount = in.readInt();
        if (entryCount < 0 || entryCount > MAX_SNAPSHOT_ENTRIES) {
            throw new IOException("Invalid EDB2 entry count " + entryCount);
        }
        for (int i = 0; i < entryCount; i++) readSnapshotEntry(in);
        if (in.read() != -1) throw new IOException("Trailing bytes in EDB2 snapshot");

        log("Loaded EDB2 header version={} timestamp={} markers={} entries={}",
                fileVersion, new Date(timestamp), markerCount, entryCount);
        if (!fileVersion.equals(currentModVersion())) {
            backupDatabaseFile(fileVersion);
            dirty = true;
        }
    }

    private static void loadEdb1(DataInputStream in) throws IOException {
        String fileVersion = in.readUTF();
        int format = in.readInt();
        long timestamp = in.readLong();
        log("Loaded legacy EDB1 header version={} format={} timestamp={}",
                fileVersion, format, new Date(timestamp));
        if (!fileVersion.equals(currentModVersion())) backupDatabaseFile(fileVersion);

        while (true) {
            try {
                readSnapshotEntry(in);
            } catch (EOFException eof) {
                return;
            }
        }
    }

    private static void loadLegacyHeaderlessDatabase(DataInputStream in) throws IOException {
        while (true) {
            try {
                readSnapshotEntry(in);
            } catch (EOFException eof) {
                return;
            }
        }
    }

    private static void readSnapshotEntry(DataInputStream in) throws IOException {
        String scope = in.readUTF();
        int freq = in.readInt();
        int keyLength = in.readInt();
        validateKeyLength(keyLength);
        byte[] keyBytes = new byte[keyLength];
        in.readFully(keyBytes);
        long count = in.readLong();
        if (!validKey(scope, keyBytes) || count <= 0) {
            throw new IOException("Invalid item database entry");
        }

        AEKey key = new AEKey(scope, freq, keyBytes);
        StoredEntry previous = dbMap.get(key);
        long merged = previous == null ? count : saturatedAdd(previous.count(), count);
        dbMap.put(key, new StoredEntry(merged, deserializeAeKey(keyBytes)));
    }

    // ==== Semantic Keys and Counts ====

    private static SemanticMatch findSemanticMatchLocked(AEKey canonicalKey, AEItemKey requestedAeKey) {
        StoredEntry canonical = dbMap.get(canonicalKey);
        long canonicalCount = canonical == null ? 0L : canonical.count();
        if (requestedAeKey == null) {
            return new SemanticMatch(canonicalCount, canonicalCount, false, List.of());
        }

        long total = canonicalCount;
        boolean overflowed = false;
        List<Map.Entry<AEKey, StoredEntry>> alternates = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : frequencyEntriesLocked(
                canonicalKey.scope(), canonicalKey.freq())) {
            if (entry.getKey().equals(canonicalKey)) continue;
            AEItemKey storedAeKey = entry.getValue().aeKey();
            if (storedAeKey == null) storedAeKey = deserializeAeKey(entry.getKey().itemBytes());
            if (requestedAeKey.equals(storedAeKey)) {
                long count = entry.getValue().count();
                if (count > Long.MAX_VALUE - total) {
                    total = Long.MAX_VALUE;
                    overflowed = true;
                } else {
                    total += count;
                }
                alternates.add(entry);
            }
        }
        return new SemanticMatch(canonicalCount, total, overflowed, List.copyOf(alternates));
    }

    private static List<Map.Entry<AEKey, StoredEntry>> frequencyEntriesLocked(String scope, int freq) {
        if (scope == null) return List.of();
        AEKey first = new AEKey(scope, freq, new byte[0]);
        List<Map.Entry<AEKey, StoredEntry>> result = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.tailMap(first, true).entrySet()) {
            AEKey key = entry.getKey();
            if (!scope.equals(key.scope()) || key.freq() != freq) break;
            result.add(Map.entry(key, entry.getValue()));
        }
        return result;
    }

    private static List<Map.Entry<AEKey, StoredEntry>> scopeEntriesLocked(String scope) {
        AEKey first = new AEKey(scope, Integer.MIN_VALUE, new byte[0]);
        List<Map.Entry<AEKey, StoredEntry>> result = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.tailMap(first, true).entrySet()) {
            if (!scope.equals(entry.getKey().scope())) break;
            result.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static int frequencySizeLocked(String scope, int freq) {
        return frequencyEntriesLocked(scope, freq).size();
    }

    private static long clampDelta(long current, long requestedDelta) {
        if (requestedDelta > 0) return Math.min(requestedDelta, Long.MAX_VALUE - current);
        if (requestedDelta < 0) {
            long requestedRemoval = requestedDelta == Long.MIN_VALUE ? Long.MAX_VALUE : -requestedDelta;
            return -Math.min(current, requestedRemoval);
        }
        return 0L;
    }

    private static long applyClampedDelta(long current, long clampedDelta) {
        if (clampedDelta >= 0) return current + clampedDelta;
        return current - (-clampedDelta);
    }

    private static long saturatedAdd(long left, long right) {
        if (left < 0 || right < 0) throw new IllegalArgumentException("Counts must be non-negative");
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static AEItemKey deserializeAeKey(byte[] itemBytes) {
        try {
            ItemStack stack = deserializeItemStackFromBytes(itemBytes);
            return stack.isEmpty() ? null : AEItemKey.of(stack);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean validKey(String scopePrefix, byte[] itemBytes) {
        return scopePrefix != null && !scopePrefix.isEmpty()
                && itemBytes != null && itemBytes.length > 0 && itemBytes.length <= MAX_KEY_BYTES;
    }

    private static void validateKeyLength(int length) throws IOException {
        if (length <= 0 || length > MAX_KEY_BYTES) throw new IOException("Invalid item key length " + length);
    }

    private static void invalidateFrequencyCache(String scope, int freq) {
        itemCountCache.remove(scope + "|" + freq);
    }

    // ==== File Utilities ====

    private static byte[] createWalRecord(String scope, int freq, byte[] itemBytes, long delta)
            throws IOException {
        if (!validKey(scope, itemBytes)) throw new IOException("Invalid item WAL key");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(scope);
            out.writeInt(freq);
            out.writeInt(itemBytes.length);
            out.write(itemBytes);
            out.writeLong(delta);
            out.flush();
            byte[] record = bytes.toByteArray();
            if (record.length > MAX_WAL_RECORD_BYTES) throw new IOException("Item WAL record is too large");
            return record;
        }
    }

    private static byte[] createWalTransaction(List<byte[]> operations) throws IOException {
        if (operations.isEmpty() || operations.size() > MAX_WAL_TRANSACTION_OPERATIONS) {
            throw new IOException("Invalid item WAL transaction operation count " + operations.size());
        }

        long encodedLength = Integer.BYTES * 2L;
        for (byte[] operation : operations) {
            if (operation == null || operation.length <= 0 || operation.length > MAX_WAL_RECORD_BYTES) {
                throw new IOException("Invalid item WAL operation length");
            }
            encodedLength += Integer.BYTES + (long) operation.length;
            if (encodedLength > MAX_WAL_RECORD_BYTES) {
                throw new IOException("Item WAL transaction exceeds " + MAX_WAL_RECORD_BYTES + " bytes");
            }
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) encodedLength);
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(WAL_TRANSACTION_MAGIC);
            out.writeInt(operations.size());
            for (byte[] operation : operations) {
                out.writeInt(operation.length);
                out.write(operation);
            }
            out.flush();
            return bytes.toByteArray();
        }
    }

    private static void openWALStream() throws IOException {
        if (currentWAL == null) throw new IOException("Item WAL path is not configured");
        walFileStream = new FileOutputStream(currentWAL, true);
    }

    private static void closeWalQuietly() {
        if (walFileStream == null) return;
        try {
            walFileStream.close();
        } catch (IOException e) {
            LOGGER.warn("Could not close the item WAL stream", e);
        } finally {
            walFileStream = null;
        }
    }

    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static long checksumPrefix(File file, long length) throws IOException {
        if (length < 0 || length > file.length()) throw new IOException("Invalid WAL checksum length " + length);
        CRC32 crc = new CRC32();
        long remaining = length;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new EOFException("WAL ended while calculating its checkpoint checksum");
                crc.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return crc.getValue();
    }

    private static void replaceDatabaseFile(File temp, File target) throws IOException {
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is unavailable on some Windows/JDK combinations.
        }
    }

    private static Map<String, WalCheckpoint> checkpointMap(List<WalCheckpoint> checkpoints) {
        Map<String, WalCheckpoint> result = new HashMap<>();
        for (WalCheckpoint checkpoint : checkpoints) result.put(checkpoint.fileName(), checkpoint);
        return Map.copyOf(result);
    }

    private static boolean isSafeWalMarker(String fileName, long length) {
        if (length < 0) return false;
        if (WAL_FILE_NAME.equals(fileName)) return true;
        if (!fileName.startsWith(ROTATED_WAL_PREFIX)) return false;
        return fileName.substring(ROTATED_WAL_PREFIX.length()).matches("\\d+");
    }

    private static String currentModVersion() {
        try {
            ModList modList = ModList.get();
            if (modList == null) return "unknown";
            return modList.getModContainerById(Constants.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            LOGGER.warn("Could not resolve the EnderDrives version from ModList", e);
            return "unknown";
        }
    }

    private static void migrateOldRecords() {
        List<Map.Entry<AEKey, StoredEntry>> oldEntries = dbMap.entrySet().stream()
                .filter(entry -> {
                    String scope = entry.getKey().scope();
                    return scope == null || scope.isEmpty()
                            || (!scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global"));
                })
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();

        for (Map.Entry<AEKey, StoredEntry> entry : oldEntries) {
            AEKey oldKey = entry.getKey();
            StoredEntry value = dbMap.get(oldKey);
            if (value == null || value.count() <= 0) continue;

            AEKey newKey = new AEKey("global", oldKey.freq(), oldKey.itemBytes());
            StoredEntry existing = dbMap.get(newKey);
            long existingCount = existing == null ? 0L : existing.count();
            long movable = Math.min(value.count(), Long.MAX_VALUE - existingCount);
            if (movable == 0) continue;

            AEItemKey aeKey = existing != null && existing.aeKey() != null ? existing.aeKey() : value.aeKey();
            dbMap.put(newKey, new StoredEntry(existingCount + movable, aeKey));
            if (movable == value.count()) {
                dbMap.remove(oldKey);
            } else {
                dbMap.put(oldKey, new StoredEntry(value.count() - movable, value.aeKey()));
            }
            dirty = true;
        }
    }

    private static void backupDatabaseFile(String version) {
        String timestamp = LocalDateTime.now().toString().replace(":", "-");
        File backupZip = new File(dbFile.getParent(),
                String.format("enderdrives_%s_%s.zip", version, timestamp));
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(backupZip))) {
            zipFile(dbFile, out);
            if (currentWAL != null && currentWAL.exists()) zipFile(currentWAL, out);
            LOGGER.info("Backed up item database to {} because its mod version changed", backupZip.getName());
        } catch (IOException e) {
            LOGGER.error("Could not back up the previous item database", e);
        }
    }

    private static void zipFile(File file, ZipOutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            out.putNextEntry(new ZipEntry(file.getName()));
            in.transferTo(out);
            out.closeEntry();
        }
    }

    private static void failLifecycleLocked(String reason) {
        lifecycle = Lifecycle.FAILED;
        initialized = false;
        running = false;
        LOGGER.error(reason);
    }

    private static void log(String format, Object... args) {
        if (debugLog) LOGGER.info("[EnderDBManager] " + format, args);
    }

    private enum Lifecycle {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }

    private record CachedCount(long count, long timestamp) {}
    private record WalCheckpoint(String fileName, long length, long checksum) {}
    private record WalOperation(String scope, int freq, byte[] itemBytes, long delta) {}
    private record ScopeMigration(AEKey source, AEKey destination, long mergedCount) {}
    private record SemanticMatch(
            long canonicalCount,
            long total,
            boolean overflowed,
            List<Map.Entry<AEKey, StoredEntry>> alternateEntries
    ) {}
}
