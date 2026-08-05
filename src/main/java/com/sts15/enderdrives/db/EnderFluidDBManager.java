package com.sts15.enderdrives.db;

import appeng.api.stacks.AEFluidKey;
import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.inventory.EnderFluidDiskInventory;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.sts15.enderdrives.inventory.EnderFluidDiskInventory.deserializeFluidStackFromBytes;

/**
 * Fluid-only backing store for EnderDrives, modeled after EnderDBManager.
 * Stores counts in "mB units" (AE2 fluid units), keyed by (scope, frequency, serialized FluidStack).
 *
 *   - enderdrives_fluids.bin
 *   - enderdrives_fluids.wal
 */
public class EnderFluidDBManager {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    public static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();
    private static final ConcurrentHashMap<String, CachedCount> amountCache = new ConcurrentHashMap<>();
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    public static volatile boolean running = false, dirty = false;
    private static Thread commitThread = null;
    private static volatile boolean initialized;
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();
    private static final AtomicLong totalRecordsWritten = new AtomicLong(0);
    private static final AtomicLong totalDbCommits = new AtomicLong(0);
    private static volatile long minDbCommitMs = 1_000L;
    private static volatile boolean debugLog;
    private static long lastDbCommitTime  = System.currentTimeMillis();
    private static final int MAX_WAL_RECORD_BYTES = 64 * 1024 * 1024;
    private static final int MAX_KEY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SCOPE_BYTES = 32_767;
    private static final int WAL_TRANSACTION_MAGIC = 0x45465732; // EFW2
    private static final int WAL_GENERATION_MAGIC = 0x45464732; // EFG2
    private static final String SNAPSHOT_MAGIC = "EFDB2";
    private static final String LEGACY_SNAPSHOT_MAGIC = "EFDB1";
    private static final String WAL_FILE_NAME = "enderdrives_fluids.wal";
    private static final String ROTATED_WAL_PREFIX = WAL_FILE_NAME + ".";
    private static final int SNAPSHOT_FORMAT = 2;
    private static final int MAX_SNAPSHOT_ENTRIES = 10_000_000;
    private static final int MAX_WAL_MARKERS = 10_000;
    private static final int MAX_WAL_TRANSACTION_OPERATIONS = 1_000_000;
    private static final Map<String, WalIdentity> checkpointedWalPrefixes = new HashMap<>();
    private static final List<ReplayFile> replayedWalFiles = new ArrayList<>();

    // ==== Public API =================================================================================================

    public static synchronized void init() {
        if (initialized && running && commitThread != null && commitThread.isAlive()) return;

        running = false;
        stopBackgroundCommitThread();

        try {
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT)
                    .resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);

            synchronized (commitLock) {
                closeWALStreamQuietly();
                dbFile = worldDir.resolve("enderdrives_fluids.bin").toFile();
                currentWAL = worldDir.resolve(WAL_FILE_NAME).toFile();
                minDbCommitMs = Math.max(100L, serverConfig.END_DB_MIN_DB_COMMIT_INTERVAL_MS.get());
                debugLog = serverConfig.END_DB_DEBUG_LOG.get();

                dbMap.clear();
                amountCache.clear();
                replayedWalFiles.clear();
                checkpointedWalPrefixes.clear();
                dirty = false;
                lastDbCommitTime = System.currentTimeMillis();

                loadDatabase();
                replayWALs();
                prepareCurrentWALForAppendLocked();
                migrateOldRecords();
                if (dirty && !commitDatabase()) {
                    throw new IOException("Could not checkpoint recovered fluid storage");
                }
                initialized = true;
                running = true;
            }

            startBackgroundCommit();

            if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(
                        new Thread(EnderFluidDBManager::shutdown, "EnderDrives-FluidDB-Shutdown"));
            }
        } catch (Exception e) {
            running = false;
            initialized = false;
            closeWALStreamQuietly();
            LOGGER.error("Failed to initialize the fluid database", e);
        }
    }

    public static void clearRAMCaches() {
        amountCache.clear();
        log("[clearRAMCaches] Derived fluid caches cleared.");
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        running = false;
        stopBackgroundCommitThread();

        boolean checkpointed;
        synchronized (commitLock) {
            checkpointed = commitDatabase();
            closeWALStreamQuietly();
            if (checkpointed) {
                dbMap.clear();
                amountCache.clear();
                replayedWalFiles.clear();
            } else {
                LOGGER.error("Final fluid database checkpoint failed; retaining RAM state and WAL recovery files");
            }
        }

        initialized = false;
    }

    private static void stopBackgroundCommitThread() {
        Thread thread = commitThread;
        commitThread = null;
        if (thread == null || thread == Thread.currentThread()) return;
        thread.interrupt();
        try {
            thread.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Save a fluid delta (in mB units) into the database.
     *
     * @param scopePrefix scope (player_/team_/global)
     * @param freq        frequency id
     * @param fluidBytes  serialized FluidStack (NBT bytes)
     * @param delta       positive to add, negative to remove (mB)
     */
    public static long saveFluid(String scopePrefix, int freq, byte[] fluidBytes, long delta) {
        return mutateFluid(scopePrefix, freq, fluidBytes, delta, 0, false);
    }

    /**
     * Atomically inserts fluid while enforcing the disk's type limit under the
     * same lock as the persisted mutation.
     */
    public static long insertFluid(
            String scopePrefix,
            int freq,
            byte[] fluidBytes,
            long requestedAmount,
            int typeLimit
    ) {
        if (requestedAmount <= 0) return 0L;
        return mutateFluid(scopePrefix, freq, fluidBytes, requestedAmount, Math.max(0, typeLimit), true);
    }

    /** Durably merges every fluid entry from one scope namespace into another. */
    public static boolean migrateScope(String oldScope, String newScope) {
        if (!isValidScope(oldScope) || !isValidScope(newScope)) return false;
        if (oldScope.equals(newScope)) return true;
        synchronized (commitLock) {
            if (!initialized || !running || walWriter == null || walFileStream == null) return false;
            List<Map.Entry<AEKey, StoredEntry>> sourceEntries = dbMap.entrySet().stream()
                    .filter(entry -> entry.getKey().scope().equals(oldScope))
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                    .toList();
            if (sourceEntries.isEmpty()) return true;
            if (sourceEntries.size() > MAX_WAL_TRANSACTION_OPERATIONS / 2) {
                LOGGER.error("Refusing fluid scope migration with too many entries: {}", sourceEntries.size());
                return false;
            }

            List<DeltaOperation> operations = new ArrayList<>(sourceEntries.size() * 2);
            for (var source : sourceEntries) {
                AEKey sourceKey = source.getKey();
                StoredEntry sourceValue = source.getValue();
                AEKey destinationKey = new AEKey(newScope, sourceKey.freq(), sourceKey.itemBytes());
                long destinationCount = dbMap.getOrDefault(destinationKey, StoredEntry.EMPTY).count();
                long accepted = acceptedDelta(destinationCount, sourceValue.count());

                operations.add(new DeltaOperation(
                        oldScope, sourceKey.freq(), sourceKey.itemBytes(), -sourceValue.count(), sourceValue.aeKey()));
                if (accepted > 0L) {
                    operations.add(new DeltaOperation(
                            newScope, sourceKey.freq(), sourceKey.itemBytes(), accepted, sourceValue.aeKey()));
                }
            }
            if (!appendWalTransactionLocked(operations)) return false;
            operations.forEach(EnderFluidDBManager::applyDeltaLocked);
            sourceEntries.forEach(entry -> {
                invalidateAmountCache(oldScope, entry.getKey().freq());
                invalidateAmountCache(newScope, entry.getKey().freq());
            });
            dirty = true;
            return true;
        }
    }

    private static long mutateFluid(
            String scopePrefix,
            int freq,
            byte[] fluidBytes,
            long requestedDelta,
            int typeLimit,
            boolean enforceTypeLimit
    ) {
        if (requestedDelta == 0L || !isValidKeyInput(scopePrefix, fluidBytes)) return 0L;

        synchronized (commitLock) {
            if (!initialized || !running || walWriter == null || walFileStream == null) return 0L;

            CanonicalFluid canonical = canonicalizeFluid(fluidBytes);
            if (canonical == null) return 0L;

            AEKey canonicalKey = new AEKey(scopePrefix, freq, canonical.bytes());
            SemanticFluidMatch match = findSemanticMatchLocked(canonicalKey, canonical.key());
            if (match.overflowed()) {
                LOGGER.error("Refusing to mutate a fluid whose legacy semantic aliases exceed Long.MAX_VALUE");
                return 0L;
            }

            long current = match.total();
            if (requestedDelta > 0L && enforceTypeLimit && current == 0L
                    && getTypeCountLocked(scopePrefix, freq) >= typeLimit) {
                return 0L;
            }

            long accepted = acceptedDelta(current, requestedDelta);
            if (accepted == 0L && match.alternateEntries().isEmpty()) return 0L;

            long targetCount = accepted >= 0L ? current + accepted : current - (-accepted);
            List<DeltaOperation> operations = createRewriteOperations(
                    canonicalKey, canonical.key(), match, targetCount);
            if (!appendWalTransactionLocked(operations)) return 0L;

            operations.forEach(EnderFluidDBManager::applyDeltaLocked);
            invalidateAmountCache(scopePrefix, freq);
            dirty = true;
            return accepted;
        }
    }

    /** Current stored amount (mB) for a specific fluid key, with semantic fallback + merge. */
    public static long getFluidAmount(String scopePrefix, int freq, byte[] keyBytes) {
        if (!isValidKeyInput(scopePrefix, keyBytes)) return 0L;
        CanonicalFluid requested = canonicalizeFluid(keyBytes);
        if (requested == null) return 0L;

        synchronized (commitLock) {
            AEKey canonicalKey = new AEKey(scopePrefix, freq, requested.bytes());
            SemanticFluidMatch match = findSemanticMatchLocked(canonicalKey, requested.key());
            long total = match.total();
            if (match.alternateEntries().isEmpty() || match.overflowed()
                    || !initialized || !running || walWriter == null || walFileStream == null) {
                return total;
            }

            List<DeltaOperation> migration = createRewriteOperations(
                    canonicalKey, requested.key(), match, total);
            if (appendWalTransactionLocked(migration)) {
                migration.forEach(EnderFluidDBManager::applyDeltaLocked);
                invalidateAmountCache(scopePrefix, freq);
                dirty = true;
            }
            return total;
        }
    }

    /** Number of unique fluid types including pending (range by scope|freq). */
    public static int getTypeCountInclusive(String scope, int freq) {
        synchronized (commitLock) {
            return getTypeCountLocked(scope, freq);
        }
    }

    /** Total amount (mB) including pending for a scope|freq. */
    public static long getTotalAmountInclusive(String scope, int freq) {
        long total = 0L;
        for (Map.Entry<AEKey, StoredEntry> e : frequencyView(scope, freq).entrySet()) {
            total = saturatedAdd(total, e.getValue().count());
        }
        return total;
    }

    /** Clears all entries for a given frequency + scope. */
    public static void clearFrequency(String scopePrefix, int frequency) {
        synchronized (commitLock) {
            if (!initialized || !running || walWriter == null) return;
            NavigableMap<AEKey, StoredEntry> sub = frequencyView(scopePrefix, frequency);
            List<Map.Entry<AEKey, StoredEntry>> removedEntries = List.copyOf(sub.entrySet());
            if (removedEntries.isEmpty()) return;

            List<DeltaOperation> operations = new ArrayList<>(removedEntries.size());
            for (var entry : removedEntries) {
                operations.add(new DeltaOperation(
                        scopePrefix,
                        frequency,
                        entry.getKey().itemBytes(),
                        -entry.getValue().count(),
                        entry.getValue().aeKey()));
            }
            if (!appendWalTransactionLocked(operations)) return;

            sub.clear();
            invalidateAmountCache(scopePrefix, frequency);
            dirty = true;
            log("[clearFrequency] Cleared (fluids) freq {} scope {} ({} entries)",
                    frequency, scopePrefix, removedEntries.size());
        }
    }

    /** Unique fluid type count for scope|freq. */
    public static int getTypeCount(String scopePrefix, int freq) {
        synchronized (commitLock) {
            return getTypeCountLocked(scopePrefix, freq);
        }
    }

    /** Query all fluids (AEFluidKey + count) for a scope|freq, with lazy AEFluidKey repair. */
    public static List<FluidKeyCacheEntry> queryFluidsByFrequency(String scopePrefix, int freq) {
        synchronized (commitLock) {
            List<FluidKeyCacheEntry> result = new ArrayList<>();
            for (var entry : frequencyView(scopePrefix, freq).entrySet()) {
                long count = entry.getValue().count();
                if (count <= 0L) continue;
                AEFluidKey fluidKey = recoverFluidKey(
                        entry.getKey().itemBytes(), entry.getValue().aeKey());
                if (fluidKey != null) {
                    result.add(new FluidKeyCacheEntry(entry.getKey(), fluidKey, count));
                }
            }
            return result;
        }
    }

    /** Cached total amount (mB) for scope|freq, recalculated at most once per second. */
    public static long getTotalAmount(String scopePrefix, int frequency) {
        String key = scopePrefix + "|" + frequency;
        CachedCount cached = amountCache.get(key);
        long now = System.currentTimeMillis();

        if (cached == null || (now - cached.timestamp) >= 1000) {
            long newCount = calculateTotalAmount(scopePrefix, frequency);
            amountCache.put(key, new CachedCount(newCount, now));
            log("[getTotalAmount] Recalculated fluid amount: scope={} freq={} total={}", scopePrefix, frequency, newCount);
            return newCount;
        }
        log("[getTotalAmount] Using cached fluid amount: scope={} freq={} total={}", scopePrefix, frequency, cached.count);
        return cached.count;
    }

    private static long calculateTotalAmount(String scopePrefix, int frequency) {
        long total = 0L;
        for (FluidKeyCacheEntry entry : queryFluidsByFrequency(scopePrefix, frequency)) {
            total = saturatedAdd(total, entry.count());
        }
        return total;
    }

    /**
     * Writes the current map to the DB file (atomic replace).
     * EFDB2 includes checkpointed WAL prefixes so replacing the base file and
     * truncating WALs remain recoverable as separate durable steps.
     */
    public static boolean commitDatabase() {
        synchronized (commitLock) {
            if (dbFile == null || currentWAL == null || walWriter == null || walFileStream == null) return false;
            if (!flushWALQueueLocked()) return false;

            Map<String, WalIdentity> includedPrefixes = collectIncludedWalPrefixesLocked();
            if (!includedPrefixes.containsKey(currentWAL.getName())) return false;
            if (!writeDatabaseSnapshotLocked(includedPrefixes)) return false;

            boolean recoveredFilesHandled = cleanupReplayedWALsLocked();
            if (recoveredFilesHandled) {
                if (!resetCurrentWALLocked()) return false;
                if (!writeDatabaseSnapshotLocked(Map.of())) {
                    running = false;
                    LOGGER.error("Could not clear the old fluid WAL checkpoint markers; mutations are disabled");
                    return false;
                }
                checkpointedWalPrefixes.clear();
            } else {
                checkpointedWalPrefixes.clear();
                checkpointedWalPrefixes.putAll(includedPrefixes);
            }
            dirty = false;
            totalDbCommits.incrementAndGet();
            lastDbCommitTime = System.currentTimeMillis();
            log("[commitDatabase] Fluids DB committed.");
            return true;
        }
    }

    private static boolean writeDatabaseSnapshotLocked(Map<String, WalIdentity> includedPrefixes) {
        File temp = new File(dbFile.getAbsolutePath() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(temp);
                 DataOutputStream dos = new DataOutputStream(
                         new BufferedOutputStream(fos, 512 * 1024))) {
                dos.writeUTF(SNAPSHOT_MAGIC);
                dos.writeUTF(getCurrentModVersion());
                dos.writeInt(SNAPSHOT_FORMAT);
                dos.writeLong(System.currentTimeMillis());

                dos.writeInt(includedPrefixes.size());
                for (var checkpoint : includedPrefixes.entrySet()) {
                    dos.writeUTF(checkpoint.getKey());
                    dos.writeLong(checkpoint.getValue().length());
                    dos.writeLong(checkpoint.getValue().checksum());
                }

                dos.writeInt(dbMap.size());
                for (var entry : dbMap.entrySet()) {
                    AEKey key = entry.getKey();
                    dos.writeUTF(key.scope());
                    dos.writeInt(key.freq());
                    dos.writeInt(key.itemBytes().length);
                    dos.write(key.itemBytes());
                    dos.writeLong(entry.getValue().count());
                }
                dos.flush();
                fos.getFD().sync();
            }
            replaceDatabaseFile(temp, dbFile);
            forceDirectory(dbFile.toPath().getParent());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to checkpoint the fluid database", e);
            return false;
        } finally {
            if (temp.exists() && !temp.equals(dbFile) && !temp.delete()) {
                log("Could not delete temporary fluid snapshot {}", temp);
            }
        }
    }

    public static AtomicLong getTotalRecordsWritten() { return totalRecordsWritten; }
    public static AtomicLong getTotalDbCommits() { return totalDbCommits; }
    public static int       getDatabaseSize() { return dbMap.size(); }
    public static long      getDatabaseFileSizeBytes() { return dbFile != null && dbFile.exists() ? dbFile.length() : 0; }

    private static void startBackgroundCommit() {
        if (commitThread != null && commitThread.isAlive()) {
            log("[startBackgroundCommit] Fluid commit thread already running; skipping.");
            return;
        }

        commitThread = new Thread(() -> {
            log("[startBackgroundCommit] Fluid checkpoint thread starting...");
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(100L);
                    long now = System.currentTimeMillis();
                    if (dirty && now - lastDbCommitTime >= minDbCommitMs) {
                        if (!commitDatabase()) {
                            LOGGER.warn("Background fluid database checkpoint failed; WAL remains authoritative");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Fluid background checkpoint error", e);
                }
            }
        }, "EnderFluidDB-CommitThread");

        commitThread.setDaemon(true);
        commitThread.start();
    }

    private static boolean applyBinaryOperation(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_WAL_RECORD_BYTES) return false;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            dis.mark(data.length);
            if (data.length >= Integer.BYTES) {
                int magic = dis.readInt();
                if (magic == WAL_GENERATION_MAGIC) {
                    if (dis.available() != Long.BYTES * 2) return false;
                    dis.readLong();
                    dis.readLong();
                    return dis.available() == 0;
                }
                if (magic == WAL_TRANSACTION_MAGIC) {
                    int operationCount = dis.readInt();
                    if (operationCount <= 0 || operationCount > MAX_WAL_TRANSACTION_OPERATIONS
                            || operationCount > dis.available() / (Integer.BYTES + 1)) return false;
                    List<DeltaOperation> operations = new ArrayList<>(operationCount);
                    for (int i = 0; i < operationCount; i++) {
                        int operationLength = dis.readInt();
                        if (operationLength <= 0 || operationLength > MAX_WAL_RECORD_BYTES
                                || operationLength > dis.available()) return false;
                        byte[] operationBytes = dis.readNBytes(operationLength);
                        if (operationBytes.length != operationLength) return false;
                        DeltaOperation operation = parseDeltaOperation(operationBytes);
                        if (operation == null) return false;
                        operations.add(operation);
                    }
                    if (dis.available() != 0) return false;
                    operations.forEach(EnderFluidDBManager::applyRecoveredDeltaLocked);
                    dirty = true;
                    return true;
                }
            }

            dis.reset();
            DeltaOperation legacyOperation = parseDeltaOperation(data);
            if (legacyOperation == null) return false;
            applyRecoveredDeltaLocked(legacyOperation);
            dirty = true;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static DeltaOperation readDeltaOperation(DataInputStream dis) throws IOException {
        String scopePrefix = dis.readUTF();
        if (modifiedUtfLength(scopePrefix) > MAX_SCOPE_BYTES) return null;
        int freq = dis.readInt();
        int keyLen = dis.readInt();
        if (keyLen <= 0 || keyLen > MAX_KEY_BYTES || keyLen > dis.available() - Long.BYTES) return null;
        byte[] keyBytes = dis.readNBytes(keyLen);
        if (keyBytes.length != keyLen) return null;
        long delta = dis.readLong();
        if (delta == 0L) return null;
        CanonicalFluid canonical = canonicalizeFluid(keyBytes);
        if (canonical == null) return null;
        return new DeltaOperation(scopePrefix, freq, canonical.bytes(), delta, canonical.key());
    }

    private static DeltaOperation parseDeltaOperation(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            DeltaOperation operation = readDeltaOperation(input);
            return operation != null && input.available() == 0 ? operation : null;
        }
    }

    public static void flushWALQueue() {
        synchronized (commitLock) {
            flushWALQueueLocked();
        }
    }

    private static boolean flushWALQueueLocked() {
        if (walWriter == null || walFileStream == null) return false;
        try {
            walWriter.flush();
            walFileStream.getFD().sync();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to flush the fluid WAL", e);
            return false;
        }
    }

    private static void replayWALs() throws IOException {
        File directory = currentWAL.getParentFile();
        File[] rotatedWALs = directory.listFiles((dir, name) ->
                name.startsWith(ROTATED_WAL_PREFIX)
                        && name.substring(ROTATED_WAL_PREFIX.length()).matches("\\d+"));
        if (rotatedWALs != null) {
            Arrays.sort(rotatedWALs, Comparator.comparingLong(EnderFluidDBManager::rotatedWalSequence));
            for (File rotated : rotatedWALs) replayWAL(rotated, false);
        }
        if (currentWAL.exists() && currentWAL.length() > 0L) replayWAL(currentWAL, true);
    }

    private static void replayWAL(File walFile, boolean current) throws IOException {
        long startOffset = matchingCheckpointPrefixLength(walFile);
        long lastValidOffset = startOffset;
        boolean damaged = false;

        try (RandomAccessFile input = new RandomAccessFile(walFile, "r")) {
            long fileLength = input.length();
            if (startOffset < 0L || startOffset > fileLength) startOffset = 0L;
            input.seek(startOffset);
            lastValidOffset = startOffset;

            while (input.getFilePointer() < fileLength) {
                long recordStart = input.getFilePointer();
                if (fileLength - recordStart < Integer.BYTES) {
                    damaged = true;
                    break;
                }
                int length = input.readInt();
                long remaining = fileLength - input.getFilePointer();
                if (length <= 0 || length > MAX_WAL_RECORD_BYTES || remaining < (long) length + Long.BYTES) {
                    damaged = true;
                    break;
                }

                byte[] data = new byte[length];
                input.readFully(data);
                long storedChecksum = input.readLong();
                if (checksum(data) != storedChecksum || !applyBinaryOperation(data)) {
                    damaged = true;
                    break;
                }
                lastValidOffset = input.getFilePointer();
            }
        }

        if (damaged) {
            preserveDamagedWAL(walFile);
            try (RandomAccessFile output = new RandomAccessFile(walFile, "rw")) {
                output.setLength(lastValidOffset);
                output.getFD().sync();
            }
            LOGGER.warn("Preserved damaged fluid WAL {} and truncated recovery copy to {} valid bytes",
                    walFile.getName(), lastValidOffset);
        }

        WalIdentity safePrefix = identityForPrefix(walFile, lastValidOffset);
        replayedWalFiles.add(new ReplayFile(walFile, current, safePrefix, damaged));
    }

    private static void openWALStream() throws IOException {
        if (currentWAL == null) throw new IllegalStateException("currentWAL file is not set (fluids)!");
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter     = new DataOutputStream(new BufferedOutputStream(walFileStream));
    }

    private static void prepareCurrentWALForAppendLocked() throws IOException {
        openWALStream();
        if (currentWAL.length() == 0L && !appendWalPayloadLocked(createGenerationRecord())) {
            throw new IOException("Could not initialize the fluid WAL generation marker");
        }
    }

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

    private static void loadDatabase() throws IOException {
        if (!dbFile.exists() || dbFile.length() == 0) return;

        String currentVersion = getCurrentModVersion();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)))) {
            dis.mark(128);
            String header;
            try {
                header = dis.readUTF();
            } catch (UTFDataFormatException malformedHeader) {
                dis.reset();
                backupDatabaseFile("0.0.0");
                dirty = true;
                while (true) {
                    try {
                        readSnapshotEntry(dis);
                    } catch (EOFException eof) {
                        return;
                    }
                }
            }
            if (SNAPSHOT_MAGIC.equals(header)) {
                String fileVer = dis.readUTF();
                int fmt = dis.readInt();
                long ts = dis.readLong();
                if (fmt != SNAPSHOT_FORMAT) throw new IOException("Unsupported EFDB2 format " + fmt);
                log("Loaded Fluid EFDB2 header ver={} fmt={} ts={}", fileVer, fmt, new Date(ts));
                if (!fileVer.equals(currentVersion)) {
                    backupDatabaseFile(fileVer);
                    dirty = true;
                }

                int checkpointCount = dis.readInt();
                if (checkpointCount < 0 || checkpointCount > MAX_WAL_MARKERS) {
                    throw new IOException("Invalid EFDB2 checkpoint count " + checkpointCount);
                }
                for (int i = 0; i < checkpointCount; i++) {
                    String fileName = dis.readUTF();
                    long length = dis.readLong();
                    long checksum = dis.readLong();
                    if (!isSafeWalMarker(fileName, length)) {
                        throw new IOException("Invalid EFDB2 WAL checkpoint marker");
                    }
                    checkpointedWalPrefixes.put(fileName, new WalIdentity(length, checksum));
                }
                if (checkpointCount > 0) dirty = true;

                int entryCount = dis.readInt();
                if (entryCount < 0 || entryCount > MAX_SNAPSHOT_ENTRIES) {
                    throw new IOException("Invalid EFDB2 entry count " + entryCount);
                }
                for (int i = 0; i < entryCount; i++) readSnapshotEntry(dis);
                if (dis.read() != -1) throw new IOException("Trailing data after EFDB2 snapshot entries");
                return;
            } else if (LEGACY_SNAPSHOT_MAGIC.equals(header)) {
                String fileVer = dis.readUTF();
                int fmt = dis.readInt();
                long ts = dis.readLong();
                log("Loaded legacy Fluid EFDB1 header ver={} fmt={} ts={}", fileVer, fmt, new Date(ts));
                if (!fileVer.equals(currentVersion)) backupDatabaseFile(fileVer);
                dirty = true;
            } else {
                dis.reset();
                backupDatabaseFile("0.0.0");
                dirty = true;
            }

            while (true) {
                try {
                    readSnapshotEntry(dis);
                } catch (EOFException eof) {
                    break;
                }
            }
        }
    }

    private static void readSnapshotEntry(DataInputStream dis) throws IOException {
        String scope = dis.readUTF();
        int freq = dis.readInt();
        int length = dis.readInt();
        if (length <= 0 || length > MAX_KEY_BYTES || length > dis.available() - Long.BYTES) {
            throw new IOException("Invalid serialized fluid key length " + length);
        }
        byte[] keyBytes = dis.readNBytes(length);
        if (keyBytes.length != length) throw new EOFException("Incomplete serialized fluid key");
        long amount = dis.readLong();
        if (amount <= 0L) return;

        CanonicalFluid canonical = canonicalizeFluid(keyBytes);
        if (canonical == null) {
            LOGGER.warn("Skipping undecodable fluid database entry for scope {} frequency {}", scope, freq);
            return;
        }
        AEKey key = new AEKey(scope, freq, canonical.bytes());
        StoredEntry previous = dbMap.getOrDefault(key, StoredEntry.EMPTY);
        dbMap.put(key, new StoredEntry(saturatedAdd(previous.count(), amount), canonical.key()));
    }

    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static byte[] createWalTransaction(List<DeltaOperation> operations) throws IOException {
        try (var baos = new ByteArrayOutputStream();
             var dos = new DataOutputStream(baos)) {
            dos.writeInt(WAL_TRANSACTION_MAGIC);
            dos.writeInt(operations.size());
            for (DeltaOperation operation : operations) {
                byte[] operationBytes = createLegacyWalOperation(operation);
                dos.writeInt(operationBytes.length);
                dos.write(operationBytes);
            }
            return baos.toByteArray();
        }
    }

    private static byte[] createLegacyWalOperation(DeltaOperation operation) throws IOException {
        try (var baos = new ByteArrayOutputStream();
             var dos = new DataOutputStream(baos)) {
            dos.writeUTF(operation.scope());
            dos.writeInt(operation.frequency());
            dos.writeInt(operation.keyBytes().length);
            dos.write(operation.keyBytes());
            dos.writeLong(operation.delta());
            return baos.toByteArray();
        }
    }

    private static byte[] createGenerationRecord() throws IOException {
        UUID generation = UUID.randomUUID();
        try (var baos = new ByteArrayOutputStream(Integer.BYTES + Long.BYTES * 2);
             var dos = new DataOutputStream(baos)) {
            dos.writeInt(WAL_GENERATION_MAGIC);
            dos.writeLong(generation.getMostSignificantBits());
            dos.writeLong(generation.getLeastSignificantBits());
            return baos.toByteArray();
        }
    }


    private static void closeWALStreamQuietly() {
        try {
            closeWALStream();
        } catch (IOException e) {
            LOGGER.warn("Failed closing the fluid WAL stream", e);
        }
    }

    private static boolean appendWalTransactionLocked(List<DeltaOperation> operations) {
        if (operations.isEmpty()) return true;
        if (operations.size() > MAX_WAL_TRANSACTION_OPERATIONS) return false;
        try {
            byte[] payload = createWalTransaction(operations);
            return appendWalPayloadLocked(payload);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to serialize a fluid WAL transaction", e);
            return false;
        }
    }

    private static boolean appendWalPayloadLocked(byte[] payload) {
        if (walWriter == null || walFileStream == null || payload.length == 0
                || payload.length > MAX_WAL_RECORD_BYTES) {
            return false;
        }
        long startLength = currentWAL.length();
        try {
            walWriter.writeInt(payload.length);
            walWriter.write(payload);
            walWriter.writeLong(checksum(payload));
            walWriter.flush();
            walFileStream.getFD().sync();
            totalRecordsWritten.incrementAndGet();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to append the fluid WAL before mutation", e);
            closeWALStreamQuietly();
            try {
                preserveDamagedWAL(currentWAL);
                try (RandomAccessFile output = new RandomAccessFile(currentWAL, "rw")) {
                    output.setLength(startLength);
                    output.getFD().sync();
                }
                openWALStream();
            } catch (IOException recoveryError) {
                running = false;
                LOGGER.error("Could not roll back a failed fluid WAL append; mutations are disabled", recoveryError);
            }
            return false;
        }
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

    private static boolean isValidKeyInput(String scopePrefix, byte[] keyBytes) {
        return isValidScope(scopePrefix)
                && keyBytes != null
                && keyBytes.length > 0
                && keyBytes.length <= MAX_KEY_BYTES;
    }

    private static boolean isValidScope(String scope) {
        return scope != null && !scope.isEmpty() && modifiedUtfLength(scope) <= MAX_SCOPE_BYTES;
    }

    private static int modifiedUtfLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static CanonicalFluid canonicalizeFluid(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length == 0 || keyBytes.length > MAX_KEY_BYTES) return null;
        try {
            FluidStack stack = deserializeFluidStackFromBytes(keyBytes);
            if (stack.isEmpty()) return null;
            AEFluidKey key = AEFluidKey.of(stack);
            byte[] identity = EnderFluidDiskInventory.serializeFluidStackToBytes(key.toStack(1));
            if (identity.length == 0 || identity.length > MAX_KEY_BYTES) return null;
            return new CanonicalFluid(identity, key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static AEFluidKey recoverFluidKey(byte[] keyBytes, AEFluidKey cached) {
        if (cached != null) return cached;
        CanonicalFluid canonical = canonicalizeFluid(keyBytes);
        return canonical == null ? null : canonical.key();
    }

    private static NavigableMap<AEKey, StoredEntry> frequencyView(String scopePrefix, int frequency) {
        AEKey from = new AEKey(scopePrefix, frequency, new byte[0]);
        AEKey to = new AEKey(scopePrefix, frequency + 1, new byte[0]);
        return dbMap.subMap(from, true, to, false);
    }

    private static int getTypeCountLocked(String scopePrefix, int frequency) {
        return frequencyView(scopePrefix, frequency).size();
    }

    private static SemanticFluidMatch findSemanticMatchLocked(AEKey canonicalKey, AEFluidKey requestedKey) {
        StoredEntry canonical = dbMap.get(canonicalKey);
        long canonicalCount = canonical == null ? 0L : canonical.count();
        long total = canonicalCount;
        boolean overflowed = false;
        List<Map.Entry<AEKey, StoredEntry>> alternates = new ArrayList<>();

        for (Map.Entry<AEKey, StoredEntry> entry
                : frequencyView(canonicalKey.scope(), canonicalKey.freq()).entrySet()) {
            if (entry.getKey().equals(canonicalKey)) continue;
            AEFluidKey storedKey = recoverFluidKey(entry.getKey().itemBytes(), entry.getValue().aeKey());
            if (!requestedKey.equals(storedKey)) continue;

            long count = entry.getValue().count();
            if (count > Long.MAX_VALUE - total) {
                total = Long.MAX_VALUE;
                overflowed = true;
            } else {
                total += count;
            }
            alternates.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return new SemanticFluidMatch(canonicalCount, total, overflowed, List.copyOf(alternates));
    }

    private static List<DeltaOperation> createRewriteOperations(
            AEKey canonicalKey,
            AEFluidKey requestedKey,
            SemanticFluidMatch match,
            long targetCount
    ) {
        List<DeltaOperation> operations = new ArrayList<>(match.alternateEntries().size() + 1);
        for (Map.Entry<AEKey, StoredEntry> alternate : match.alternateEntries()) {
            operations.add(new DeltaOperation(
                    alternate.getKey().scope(),
                    alternate.getKey().freq(),
                    alternate.getKey().itemBytes(),
                    -alternate.getValue().count(),
                    alternate.getValue().aeKey()));
        }

        long canonicalDelta = targetCount - match.canonicalCount();
        if (canonicalDelta != 0L) {
            operations.add(new DeltaOperation(
                    canonicalKey.scope(), canonicalKey.freq(), canonicalKey.itemBytes(), canonicalDelta, requestedKey));
        }
        return operations;
    }

    private static long acceptedDelta(long current, long requested) {
        current = Math.max(0L, current);
        if (requested > 0L) return Math.min(requested, Long.MAX_VALUE - current);
        if (requested < 0L) {
            long requestedRemoval = requested == Long.MIN_VALUE ? Long.MAX_VALUE : -requested;
            long removed = Math.min(current, requestedRemoval);
            return -removed;
        }
        return 0L;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static void applyDeltaLocked(DeltaOperation operation) {
        AEKey key = new AEKey(operation.scope(), operation.frequency(), operation.keyBytes());
        long current = dbMap.getOrDefault(key, StoredEntry.EMPTY).count();
        long accepted = acceptedDelta(current, operation.delta());
        long updated = saturatedAdd(current, accepted);
        if (updated <= 0L) {
            dbMap.remove(key);
        } else {
            AEFluidKey fluidKey = operation.fluidKey() != null
                    ? operation.fluidKey()
                    : recoverFluidKey(operation.keyBytes(), null);
            dbMap.put(key, new StoredEntry(updated, fluidKey));
        }
    }

    private static void applyRecoveredDeltaLocked(DeltaOperation operation) {
        applyDeltaLocked(operation);
        invalidateAmountCache(operation.scope(), operation.frequency());
    }


    private static void invalidateAmountCache(String scopePrefix, int frequency) {
        amountCache.remove(scopePrefix + "|" + frequency);
    }

    private static long matchingCheckpointPrefixLength(File walFile) throws IOException {
        WalIdentity checkpoint = checkpointedWalPrefixes.get(walFile.getName());
        if (checkpoint == null || checkpoint.length() == 0L) return 0L;
        if (walFile.length() < checkpoint.length()) {
            dirty = true;
            if (walFile.getName().equals(WAL_FILE_NAME)) {
                // A completed marked snapshot may be followed by WAL truncation and a crash.
                return 0L;
            }
            preserveDamagedWAL(walFile);
            LOGGER.warn("Checkpointed rotated fluid WAL {} is shorter than its marker; preserved and skipped",
                    walFile.getName());
            return walFile.length();
        }

        WalIdentity actual = identityForPrefix(walFile, checkpoint.length());
        if (!actual.equals(checkpoint)) {
            // The snapshot already contains this prefix. Replaying a damaged or replaced copy would
            // duplicate valid records, so retain it for diagnosis and continue after the marker.
            preserveDamagedWAL(walFile);
            dirty = true;
            LOGGER.warn("Checkpointed fluid WAL {} has a mismatched prefix; preserved and skipped",
                    walFile.getName());
        }
        return checkpoint.length();
    }

    private static WalIdentity identityForPrefix(File file, long prefixLength) throws IOException {
        if (prefixLength < 0L || file.length() < prefixLength) {
            throw new IOException("Invalid WAL prefix length " + prefixLength + " for " + file);
        }
        CRC32 crc = new CRC32();
        long remaining = prefixLength;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            while (remaining > 0L) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new EOFException("Incomplete WAL prefix in " + file);
                crc.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return new WalIdentity(prefixLength, crc.getValue());
    }

    private static Map<String, WalIdentity> collectIncludedWalPrefixesLocked() {
        Map<String, WalIdentity> result = new LinkedHashMap<>();
        for (ReplayFile replayed : replayedWalFiles) {
            if (replayed.file().exists()) result.put(replayed.file().getName(), replayed.safePrefix());
        }
        try {
            result.put(currentWAL.getName(), identityForPrefix(currentWAL, currentWAL.length()));
        } catch (IOException e) {
            LOGGER.error("Could not identify the current fluid WAL for checkpointing", e);
            return Map.of();
        }
        return result;
    }

    private static boolean cleanupReplayedWALsLocked() {
        boolean success = true;
        for (ReplayFile replayed : List.copyOf(replayedWalFiles)) {
            if (replayed.current()) continue;
            try {
                if (replayed.file().exists() && !Files.deleteIfExists(replayed.file().toPath())) success = false;
            } catch (IOException e) {
                success = false;
                LOGGER.warn("Could not remove checkpointed rotated fluid WAL {}", replayed.file(), e);
            }
        }
        if (success) replayedWalFiles.clear();
        return success;
    }

    private static boolean resetCurrentWALLocked() {
        closeWALStreamQuietly();
        try (RandomAccessFile output = new RandomAccessFile(currentWAL, "rw")) {
            output.setLength(0L);
            output.getFD().sync();
        } catch (IOException e) {
            LOGGER.error("Failed to truncate the checkpointed fluid WAL", e);
            reopenWALAfterFailure();
            return false;
        }

        try {
            openWALStream();
            if (!appendWalPayloadLocked(createGenerationRecord())) {
                running = false;
                return false;
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to reopen the checkpointed fluid WAL", e);
            running = false;
            return false;
        }
    }

    private static void reopenWALAfterFailure() {
        try {
            openWALStream();
        } catch (IOException reopenError) {
            running = false;
            LOGGER.error("Could not reopen the fluid WAL after an I/O failure", reopenError);
        }
    }

    private static void preserveDamagedWAL(File walFile) throws IOException {
        String suffix = ".corrupt." + System.currentTimeMillis();
        Path backup = walFile.toPath().resolveSibling(walFile.getName() + suffix);
        int collision = 0;
        while (Files.exists(backup)) {
            backup = walFile.toPath().resolveSibling(walFile.getName() + suffix + "." + ++collision);
        }
        Files.copy(walFile.toPath(), backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static long rotatedWalSequence(File file) {
        String name = file.getName();
        try {
            return Long.parseLong(name.substring(ROTATED_WAL_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isSafeWalMarker(String fileName, long length) {
        if (length < 0L) return false;
        if (WAL_FILE_NAME.equals(fileName)) return true;
        return fileName.startsWith(ROTATED_WAL_PREFIX)
                && fileName.substring(ROTATED_WAL_PREFIX.length()).matches("\\d+");
    }

    private static String getCurrentModVersion() {
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

    private static void log(String format, Object... args) {
        if (debugLog) LOGGER.info("[EnderFluidDB] " + format, args);
    }

    private static void migrateOldRecords() {
        List<Map.Entry<AEKey, StoredEntry>> toMigrate = dbMap.entrySet().stream()
                .filter(entry -> {
                    String scope = entry.getKey().scope();
                    return scope == null || scope.isEmpty()
                            || (!scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global"));
                })
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();

        if (toMigrate.isEmpty()) return;

        log("[migrateOldRecords] Detected {} old-format fluid records. Migrating to global scope...", toMigrate.size());

        for (Map.Entry<AEKey, StoredEntry> entry : toMigrate) {
            AEKey oldKey = entry.getKey();
            StoredEntry value = entry.getValue();
            AEKey newKey = new AEKey("global", oldKey.freq(), oldKey.itemBytes());
            if (oldKey.equals(newKey) || dbMap.get(oldKey) != value) continue;

            long existing = dbMap.getOrDefault(newKey, StoredEntry.EMPTY).count();
            long acceptedAddition = acceptedDelta(existing, value.count());
            List<DeltaOperation> migration = new ArrayList<>(2);
            migration.add(new DeltaOperation(
                    oldKey.scope(), oldKey.freq(), oldKey.itemBytes(), -value.count(), value.aeKey()));
            if (acceptedAddition > 0L) {
                migration.add(new DeltaOperation(
                        "global", oldKey.freq(), oldKey.itemBytes(), acceptedAddition, value.aeKey()));
            }
            if (!appendWalTransactionLocked(migration)) {
                LOGGER.error("Could not persist fluid scope migration for {}", oldKey);
                continue;
            }
            migration.forEach(EnderFluidDBManager::applyDeltaLocked);
            invalidateAmountCache(oldKey.scope(), oldKey.freq());
            invalidateAmountCache("global", oldKey.freq());
            dirty = true;
        }
    }

    private static void backupDatabaseFile(String version) {
        String timestamp = LocalDateTime.now().toString().replace(":", "-");
        String backupName = String.format("enderdrives_fluids_%s_%s.zip", version, timestamp);
        File backupZip = new File(dbFile.getParent(), backupName);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupZip))) {
            zipFile(dbFile, zos);
            if (currentWAL != null && currentWAL.exists()) zipFile(currentWAL, zos);
            LOGGER.info("Backed up existing fluid database to {} due to mod version change.", backupZip.getName());
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

    public record StoredEntry(long count, AEFluidKey aeKey) {
        private static final StoredEntry EMPTY = new StoredEntry(0L, null);
    }

    private record CachedCount(long count, long timestamp) {}

    private record CanonicalFluid(byte[] bytes, AEFluidKey key) {}

    private record SemanticFluidMatch(
            long canonicalCount,
            long total,
            boolean overflowed,
            List<Map.Entry<AEKey, StoredEntry>> alternateEntries
    ) {}

    private record DeltaOperation(
            String scope,
            int frequency,
            byte[] keyBytes,
            long delta,
            AEFluidKey fluidKey
    ) {}

    private record WalIdentity(long length, long checksum) {}

    private record ReplayFile(File file, boolean current, WalIdentity safePrefix, boolean damaged) {}
}
