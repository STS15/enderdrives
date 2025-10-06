package com.sts15.enderdrives.db;

import appeng.api.stacks.AEFluidKey;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.inventory.EnderFluidDiskInventory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
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
    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    private static final ConcurrentHashMap<String, CachedCount> amountCache = new ConcurrentHashMap<>();
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    public static volatile boolean running = true, dirty = false;
    private static Thread commitThread = null;
    private static final AtomicLong totalRecordsWritten = new AtomicLong(0);
    private static final AtomicLong totalDbCommits = new AtomicLong(0);
    private static final ForkJoinPool SHARED_PARALLEL_POOL =
            new ForkJoinPool(Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int    MERGE_BUFFER_THRESHOLD   = serverConfig.END_DB_MERGE_BUFFER_THRESHOLD.get();
    private static final long   MIN_WAL_COMMIT_MS        = serverConfig.END_DB_MIN_COMMIT_INTERVAL_MS.get();
    private static final long   MAX_WAL_COMMIT_MS        = serverConfig.END_DB_MAX_COMMIT_INTERVAL_MS.get();
    private static final long   MIN_DB_COMMIT_MS         = serverConfig.END_DB_MIN_DB_COMMIT_INTERVAL_MS.get();
    private static final long   MAX_DB_COMMIT_MS         = serverConfig.END_DB_MAX_DB_COMMIT_INTERVAL_MS.get();
    private static final boolean DEBUG_LOG               = serverConfig.END_DB_DEBUG_LOG.get();
    private static long lastWalCommitTime = System.currentTimeMillis();
    private static long lastDbCommitTime  = System.currentTimeMillis();

    // ==== Public API =================================================================================================

    public static void init() {
        try {
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT)
                    .resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);

            dbFile     = worldDir.resolve("enderdrives_fluids.bin").toFile();
            currentWAL = worldDir.resolve("enderdrives_fluids.wal").toFile();

            migrateOldRecords();
            loadDatabase();
            replayWALs();
            openWALStream();
            startBackgroundCommit();

            Runtime.getRuntime().addShutdownHook(new Thread(EnderFluidDBManager::shutdown));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void clearRAMCaches() {
        synchronized (commitLock) {
            dbMap.clear();
        }
        amountCache.clear();
        log("[clearRAMCaches] Fluid RAM caches cleared.");
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
     * Save a fluid delta (in mB units) into the database.
     *
     * @param scopePrefix scope (player_/team_/global)
     * @param freq        frequency id
     * @param fluidBytes  serialized FluidStack (NBT bytes)
     * @param delta       positive to add, negative to remove (mB)
     */
    public static void saveFluid(String scopePrefix, int freq, byte[] fluidBytes, long delta) {
        AEKey key = new AEKey(scopePrefix, freq, fluidBytes);

        synchronized (commitLock) {
            dbMap.compute(key, (k, existing) -> {
                long newCount = (existing == null ? 0L : existing.count()) + delta;
                if (newCount <= 0) return null;

                AEFluidKey aeKey = null;
                try {
                    FluidStack s = deserializeFluidStackFromBytes(fluidBytes);
                    if (!s.isEmpty()) aeKey = AEFluidKey.of(s);
                } catch (Exception ignored) {}

                return new StoredEntry(newCount, aeKey);
            });

            // WAL record: scope UTF, freq int, len int, bytes[], delta long
            try (var baos = new ByteArrayOutputStream();
                 var dos  = new DataOutputStream(baos)) {
                dos.writeUTF(scopePrefix);
                dos.writeInt(freq);
                dos.writeInt(fluidBytes.length);
                dos.write(fluidBytes);
                dos.writeLong(delta);
                walQueue.add(baos.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }

            dirty = true;
        }
    }

    /** Current stored amount (mB) for a specific fluid key, with semantic fallback + merge. */
    public static long getFluidAmount(String scopePrefix, int freq, byte[] keyBytes) {
        AEKey key = new AEKey(scopePrefix, freq, keyBytes);

        // Fast path: direct bytes lookup
        StoredEntry direct = dbMap.get(key);
        long directAmt = direct == null ? 0L : direct.count();
        if (directAmt > 0) return directAmt;

        // Fallback: compare by AEFluidKey equality
        AEFluidKey requestedAek = null;
        try {
            FluidStack s = deserializeFluidStackFromBytes(keyBytes);
            if (!s.isEmpty()) requestedAek = AEFluidKey.of(s);
        } catch (Exception ignored) {}

        if (requestedAek == null) return 0L;

        // Search the frequency range for legacy/alternate-byte entries
        AEKey from = new AEKey(scopePrefix, freq, new byte[0]);
        AEKey to   = new AEKey(scopePrefix, freq + 1, new byte[0]);

        long sum = 0L;
        List<AEKey> keysToMerge = new ArrayList<>();

        synchronized (commitLock) {
            NavigableMap<AEKey, StoredEntry> sub = dbMap.subMap(from, true, to, false);
            for (Map.Entry<AEKey, StoredEntry> e : sub.entrySet()) {
                StoredEntry stored = e.getValue();
                AEFluidKey storedAek = stored.aeKey();

                // Lazily recover AEFluidKey if missing
                if (storedAek == null) {
                    try {
                        FluidStack s2 = deserializeFluidStackFromBytes(e.getKey().itemBytes());
                        if (!s2.isEmpty()) storedAek = AEFluidKey.of(s2);
                    } catch (Exception ignored) {}
                }

                if (storedAek != null && storedAek.equals(requestedAek)) {
                    sum += stored.count();
                    keysToMerge.add(e.getKey());
                }
            }

            if (sum > 0) {
                // Pick a canonical key for this fluid: normalize to an identity byte[] (e.g. 1 mB)
                byte[] canonicalBytes =
                        com.sts15.enderdrives.inventory.EnderFluidDiskInventory
                                .serializeFluidStackToBytes(requestedAek.toStack(1));

                AEKey canonicalKey = new AEKey(scopePrefix, freq, canonicalBytes);
                long existing = dbMap.getOrDefault(canonicalKey, new StoredEntry(0L, requestedAek)).count();
                dbMap.put(canonicalKey, new StoredEntry(existing + sum, requestedAek));

                // Remove legacy/alternate keys
                for (AEKey k : keysToMerge) {
                    if (!Arrays.equals(k.itemBytes(), canonicalBytes)) {
                        dbMap.remove(k);
                    }
                }

                dirty = true;
            }
        }

        return sum;
    }

    /** Number of unique fluid types including pending (range by scope|freq). */
    public static int getTypeCountInclusive(String scope, int freq) {
        AEKey from = new AEKey(scope, freq, new byte[0]);
        AEKey to   = new AEKey(scope, freq + 1, new byte[0]);
        return dbMap.subMap(from, true, to, false).size();
    }

    /** Total amount (mB) including pending for a scope|freq. */
    public static long getTotalAmountInclusive(String scope, int freq) {
        long total = 0L;
        AEKey from = new AEKey(scope, freq, new byte[0]);
        AEKey to   = new AEKey(scope, freq + 1, new byte[0]);
        for (Map.Entry<AEKey, StoredEntry> e : dbMap.subMap(from, true, to, false).entrySet()) {
            total += e.getValue().count();
        }
        return total;
    }

    /** Clears all entries for a given frequency + scope. */
    public static void clearFrequency(String scopePrefix, int frequency) {
        AEKey from = new AEKey(scopePrefix, frequency, new byte[0]);
        AEKey to   = new AEKey(scopePrefix, frequency + 1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> sub = dbMap.subMap(from, true, to, false);
        int removed = sub.size();
        sub.clear();
        log("[clearFrequency] Cleared (fluids) freq {} scope {} ({} entries)", frequency, scopePrefix, removed);
    }

    /** Unique fluid type count for scope|freq. */
    public static int getTypeCount(String scopePrefix, int freq) {
        AEKey from = new AEKey(scopePrefix, freq, new byte[0]);
        AEKey to   = new AEKey(scopePrefix, freq + 1, new byte[0]);
        return dbMap.subMap(from, true, to, false).size();
    }

    /** Query all fluids (AEFluidKey + count) for a scope|freq, with lazy AEFluidKey repair. */
    public static List<FluidKeyCacheEntry> queryFluidsByFrequency(String scopePrefix, int freq) {
        AEKey lo = new AEKey(scopePrefix, freq,   new byte[0]);
        AEKey hi = new AEKey(scopePrefix, freq+1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> committed = dbMap.subMap(lo, true, hi, false);

        List<FluidKeyCacheEntry> result = new ArrayList<>();
        List<AEKey> keysToUpdate = new ArrayList<>();
        Map<AEKey, AEFluidKey> recovered = new HashMap<>();

        for (var e : committed.entrySet()) {
            long cnt = e.getValue().count();
            if (cnt <= 0) continue;

            AEKey k = e.getKey();
            AEFluidKey aek = e.getValue().aeKey();

            if (aek == null) {
                // Try to recover AEFluidKey lazily from stored bytes
                try {
                    FluidStack s = deserializeFluidStackFromBytes(k.itemBytes());
                    if (!s.isEmpty()) {
                        AEFluidKey derived = AEFluidKey.of(s);
                        aek = derived;
                        keysToUpdate.add(k);
                        recovered.put(k, derived);
                    }
                } catch (Exception ignored) {}
            }

            if (aek != null) {
                result.add(new FluidKeyCacheEntry(k, aek, cnt));
            }
        }

        // Write back any recovered AEFluidKey so future calls are fast/consistent.
        if (!keysToUpdate.isEmpty()) {
            synchronized (commitLock) {
                for (AEKey k : keysToUpdate) {
                    StoredEntry old = dbMap.get(k);
                    if (old == null) continue;
                    AEFluidKey newAek = recovered.get(k);
                    if (newAek != null) {
                        dbMap.put(k, new StoredEntry(old.count(), newAek));
                        dirty = true;
                    }
                }
            }
        }

        return result;
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
        List<FluidKeyCacheEntry> entries = queryFluidsByFrequency(scopePrefix, frequency);
        try {
            return SHARED_PARALLEL_POOL.submit(
                    () -> entries.parallelStream().mapToLong(FluidKeyCacheEntry::count).sum()
            ).get();
        } catch (Exception e) {
            e.printStackTrace();
            return 0L;
        }
    }

    /**
     * Writes the current map to the DB file (atomic replace).
     * File format mirrors EnderDBManager:
     *  - "EFDB1" (UTF), mod.version (UTF), fmt (int), ts (long)
     *  - repeated: scope UTF, freq int, len int, bytes[], count long
     */
    public static void commitDatabase() {
        try {
            File temp = new File(dbFile.getAbsolutePath() + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp), 512 * 1024))) {
                Properties props = new Properties();
                String ver = "undefined";
                try (InputStream in = EnderFluidDBManager.class.getResourceAsStream("/mod_version.properties")) {
                    if (in != null) { props.load(in); ver = props.getProperty("mod.version"); }
                }
                dos.writeUTF("EFDB1");
                dos.writeUTF(ver);
                dos.writeInt(1);
                dos.writeLong(System.currentTimeMillis());

                // entries
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
            log("[commitDatabase] Fluids DB committed.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static AtomicLong getTotalRecordsWritten() { return totalRecordsWritten; }
    public static AtomicLong getTotalDbCommits() { return totalDbCommits; }
    public static int       getDatabaseSize() { return dbMap.size(); }
    public static long      getDatabaseFileSizeBytes() { return dbFile.exists() ? dbFile.length() : 0; }

    private static void startBackgroundCommit() {

        if (commitThread != null && commitThread.isAlive()) {
            log("[startBackgroundCommit] Fluid commit thread already running; skipping.");
            return;
        }

        running = true;

        commitThread = new Thread(() -> {
            log("[startBackgroundCommit] Fluid WAL commit thread starting...");
            try {
                final int WAL_BATCH_SIZE = 100;
                long nextWalTime = System.currentTimeMillis() + MIN_WAL_COMMIT_MS;
                long nextDbTime  = System.currentTimeMillis() + MIN_DB_COMMIT_MS;

                while (running) {
                    try {
                        long now = System.currentTimeMillis();
                        int queueSize = walQueue.size();

                        boolean timeToFlushWAL = now >= nextWalTime;
                        boolean queueThresholdMet = queueSize >= WAL_BATCH_SIZE;

                        if ((timeToFlushWAL || queueThresholdMet) && queueSize > 0) {
                            synchronized (commitLock) {
                                List<byte[]> batch = new ArrayList<>();
                                walQueue.drainTo(batch, WAL_BATCH_SIZE);

                                if (!batch.isEmpty()) {
                                    log("[WAL] Flushing {} fluid entries (time={}, threshold={})",
                                            batch.size(), timeToFlushWAL, queueThresholdMet);

                                    for (byte[] rec : batch) {
                                        walWriter.writeInt(rec.length);
                                        walWriter.write(rec);
                                        walWriter.writeLong(checksum(rec));
                                        totalRecordsWritten.incrementAndGet();
                                    }
                                    walWriter.flush();
                                    lastWalCommitTime = now;
                                }
                            }
                            nextWalTime = now + MIN_WAL_COMMIT_MS;
                        }

                        if (dirty && now >= nextDbTime) {
                            synchronized (commitLock) {
                                log("[DB] Checkpoint: entries={} dirty={}", dbMap.size(), dirty);
                                commitDatabase();
                                truncateCurrentWAL();
                                lastDbCommitTime = now;
                                totalDbCommits.incrementAndGet();
                                dirty = false;
                            }
                            nextDbTime = now + MIN_DB_COMMIT_MS;
                        }

                        Thread.sleep(100);
                    } catch (Exception e) {
                        LOGGER.error("Fluid background commit error", e);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[EnderFluidDB] WAL Commit thread crashed", e);
            }
        }, "EnderFluidDB-CommitThread");

        commitThread.setDaemon(true);
        commitThread.start();
    }

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
                AEFluidKey aeKey = null;
                try {
                    FluidStack stack = deserializeFluidStackFromBytes(keyBytes);
                    if (!stack.isEmpty()) aeKey = AEFluidKey.of(stack);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                dbMap.put(key, new StoredEntry(newVal, aeKey));
            }
            log("Applying Fluid WAL: key={} delta={} old={} new={}", key, delta, oldVal, newVal);
            dirty = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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
            log("[flushWALQueue] Flushing {} fluid WAL entries on shutdown", walQueue.size());
            List<byte[]> batch = new ArrayList<>();
            walQueue.drainTo(batch);
            for (byte[] rec : batch) {
                walWriter.writeInt(rec.length);
                walWriter.write(rec);
                walWriter.writeLong(checksum(rec));
            }
            walWriter.flush();
        } catch (IOException e) {
            LOGGER.error("Error flushing fluid WAL queue during shutdown", e);
        }
    }

    private static void replayWALs() throws IOException {
        if (currentWAL.exists()) {
            replayAndDeleteWAL(currentWAL);
        }
        File dir = currentWAL.getParentFile();
        File[] rotatedWALs = dir.listFiles((d, name) ->
                name.startsWith("enderdrives_fluids.wal.") && name.matches(".*\\.\\d+$"));
        if (rotatedWALs != null) {
            Arrays.sort(rotatedWALs);
            for (File rotated : rotatedWALs) {
                replayAndDeleteWAL(rotated);
            }
        }
        truncateCurrentWAL();
    }

    private static void replayAndDeleteWAL(File walFile) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(walFile)))) {
            while (true) {
                try {
                    int length = dis.readInt();
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    long storedChecksum = dis.readLong();
                    if (checksum(data) != storedChecksum) {
                        log("Checksum mismatch for fluid record in {}", walFile.getName());
                        continue;
                    }
                    log("Replaying fluid record from {}: data length={}", walFile.getName(), length);
                    applyBinaryOperation(data);
                } catch (EOFException eof) {
                    break;
                }
            }
            if (walFile.delete()) {
                log("Deleted fluid WAL file {}", walFile.getName());
            } else {
                log("Failed to delete fluid WAL file {}", walFile.getName());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void openWALStream() throws IOException {
        if (currentWAL == null) throw new IllegalStateException("currentWAL file is not set (fluids)!");
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter     = new DataOutputStream(new BufferedOutputStream(walFileStream));
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

        Properties props = new Properties();
        String curVer = "undefined";
        try (InputStream in = EnderFluidDBManager.class.getResourceAsStream("/mod_version.properties")) {
            if (in != null) { props.load(in); curVer = props.getProperty("mod.version"); }
        }

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)))) {
            dis.mark(128);
            String header = dis.readUTF();
            if ("EFDB1".equals(header)) {
                String fileVer = dis.readUTF();
                int fmt = dis.readInt();
                long ts = dis.readLong();
                log("Loaded Fluid EFDB1 header ver={} fmt={} ts={}", fileVer, fmt, new Date(ts));
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
                long amount = dis.readLong();

                // after you computed: String scope, int freq, byte[] key, long amount
                AEFluidKey aek = null;
                try {
                    FluidStack s = deserializeFluidStackFromBytes(key);
                    if (!s.isEmpty()) {
                        aek = AEFluidKey.of(s);
                        // NEW: normalize key bytes to 1 mB identity
                        byte[] identity = EnderFluidDiskInventory.serializeFluidStackToBytes(aek.toStack(1));
                        AEKey newKey = new AEKey(scope, freq, identity);

                        StoredEntry prev = dbMap.getOrDefault(newKey, new StoredEntry(0L, aek));
                        dbMap.put(newKey, new StoredEntry(prev.count() + amount, aek));
                        continue; // skip putting the old (amount-bearing) key
                    }
                } catch (Exception x) { x.printStackTrace(); }

// fallback (empty/invalid): keep as-is if you want
                dbMap.put(new AEKey(scope, freq, key), new StoredEntry(amount, aek));

            }
        } catch (EOFException ignored) {}
    }

    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void log(String format, Object... args) {
        if (DEBUG_LOG) LOGGER.info("[EnderFluidDB] " + format, args);
    }

    private static void migrateOldRecords() {
        List<Map.Entry<AEKey, StoredEntry>> toMigrate = parallelCall(() ->
                dbMap.entrySet().parallelStream()
                        .filter(entry -> {
                            String scope = entry.getKey().scope();
                            return scope == null || scope.isEmpty()
                                    || (!scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global"));
                        })
                        .toList(), List.of()
        );

        if (toMigrate.isEmpty()) return;

        log("[migrateOldRecords] Detected {} old-format fluid records. Migrating to global scope...", toMigrate.size());

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

    public record StoredEntry(long count, AEFluidKey aeKey) {}

    private record CachedCount(long count, long timestamp) {}
}
