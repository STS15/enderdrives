package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import static com.sts15.enderdrives.inventory.EnderDiskInventory.deserializeItemStackFromBytes;

public class EnderDBManager {
    private static final ConcurrentSkipListMap<AEKey, StoredEntry> dbMap = new ConcurrentSkipListMap<>();
    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    private static File dbFile, currentWAL;
    private static FileOutputStream walFileStream;
    private static DataOutputStream walWriter;
    private static final Object commitLock = new Object();
    private static volatile boolean running = true, dirty = false;
    private static final long WAL_ROTATION_SIZE = 5 * 1024 * 1024;
    private static final int BATCH_SIZE = 100, WAL_COMMIT_THRESHOLD = 10000;
    private static final long MIN_COMMIT_INTERVAL_MS = 1000, MAX_COMMIT_INTERVAL_MS = 60000;
    private static long lastCommitTime = System.currentTimeMillis(), totalItemsWritten = 0, totalCommits = 0;
    private static final boolean DEBUG_LOG = false;

    public static void init() {
        try {
            Path worldDir = ServerLifecycleHooks.getCurrentServer()
                    .getWorldPath(LevelResource.ROOT).resolve("data").resolve("enderdrives");
            Files.createDirectories(worldDir);
            dbFile = worldDir.resolve("enderdrives.bin").toFile();
            currentWAL = worldDir.resolve("enderdrives.wal").toFile();
            loadDatabase();
            migrateOldRecords();
            openWALStream();
            replayWALs();
            startBackgroundCommit();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void shutdown() {
        running = false;
        try {
            synchronized (commitLock) {
                commitDatabase();
                truncateCurrentWAL();
                closeWALStream();
            }
        } catch (IOException ignored) {}
    }

    private static void openWALStream() throws IOException {
        walFileStream = new FileOutputStream(currentWAL, true);
        walWriter = new DataOutputStream(new BufferedOutputStream(walFileStream));
    }

    private static void closeWALStream() throws IOException {
        walWriter.close();
        walFileStream.close();
    }

    private static void loadDatabase() throws IOException {
        if (!dbFile.exists()) return;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)))) {
            dbMap.clear();
            while (dis.available() > 0) {
                dis.mark(512);
                try {
                    String scope = dis.readUTF();
                    int freq = dis.readInt();
                    int keyLen = dis.readInt();
                    byte[] keyBytes = new byte[keyLen];
                    dis.readFully(keyBytes);
                    long count = dis.readLong();
                    AEKey key = new AEKey(scope, freq, keyBytes);
                    dbMap.put(key, new StoredEntry(count, null));
                } catch (EOFException | UTFDataFormatException e) {
                    dis.reset();
                    int freq = dis.readInt();
                    int keyLen = dis.readInt();
                    byte[] keyBytes = new byte[keyLen];
                    dis.readFully(keyBytes);
                    long count = dis.readLong();
                    AEKey key = new AEKey("global", freq, keyBytes);
                    dbMap.put(key, new StoredEntry(count, null));
                }
            }
        }
    }


    private static void replayWALs() throws IOException {
        if (!currentWAL.exists()) return;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(currentWAL)))) {
            while (dis.available() > 0) {
                int length = dis.readInt();
                byte[] data = new byte[length];
                dis.readFully(data);
                long storedChecksum = dis.readLong();
                if (checksum(data) != storedChecksum) continue;
                applyBinaryOperation(data);
            }
        }
        commitDatabase();
        truncateCurrentWAL();
    }

    public static void saveItem(String scopePrefix, int freq, byte[] itemNbtBinary, long deltaCount) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF(scopePrefix);
            dos.writeInt(freq);
            dos.writeInt(itemNbtBinary.length);
            dos.write(itemNbtBinary);
            dos.writeLong(deltaCount);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        walQueue.add(baos.toByteArray());
        log("Queued WAL: scope=%s freq=%d delta=%d itemNbtSize=%d", scopePrefix, freq, deltaCount, itemNbtBinary.length);
    }

    public static long getItemCount(String scopePrefix, int freq, byte[] itemNbtBinary) {
        AEKey key = new AEKey(scopePrefix, freq, itemNbtBinary);
        return dbMap.getOrDefault(key, new StoredEntry(0L, null)).count();
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
                AEItemKey existing = dbMap.get(key) != null ? dbMap.get(key).aeKey() : null;
                dbMap.put(key, new StoredEntry(newVal, existing));
            }
            log("Applying WAL: key=%s delta=%d old=%d new=%d", key, delta, oldVal, newVal);
            dirty = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void startBackgroundCommit() {
        Thread t = new Thread(() -> {
            List<byte[]> batch = new ArrayList<>();
            while (running) {
                try {
                    walQueue.drainTo(batch, BATCH_SIZE);
                    long now = System.currentTimeMillis();
                    boolean shouldCommit = !batch.isEmpty() || walQueue.size() >= WAL_COMMIT_THRESHOLD || now - lastCommitTime >= MAX_COMMIT_INTERVAL_MS;
                    if (shouldCommit) {
                        synchronized (commitLock) {
                            for (byte[] entry : batch) {
                                walWriter.writeInt(entry.length);
                                walWriter.write(entry);
                                walWriter.writeLong(checksum(entry));
                                applyBinaryOperation(entry);
                                totalItemsWritten++;
                            }
                            walWriter.flush();
                            if (!batch.isEmpty()) {
                                log("Committed %d WAL entries. TotalItems=%d", batch.size(), totalItemsWritten);
                            }
                            rotateWalIfNeeded();
                            if (dirty && (batch.size() > 1000 || now - lastCommitTime >= MIN_COMMIT_INTERVAL_MS)) {
                                commitDatabase();
                                truncateCurrentWAL();
                                lastCommitTime = now;
                                totalCommits++;
                            }
                        }
                        batch.clear();
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

    public static void clearFrequency(String scopePrefix, int frequency) {
        AEKey from = new AEKey(scopePrefix, frequency, new byte[0]);
        AEKey to = new AEKey(scopePrefix, frequency + 1, new byte[0]);
        NavigableMap<AEKey, StoredEntry> sub = dbMap.subMap(from, true, to, false);
        int removed = sub.size();
        sub.clear();
        log("Cleared frequency %d for scope %s (%d entries)", frequency, scopePrefix, removed);
    }


    public static int getTypeCount(String scopeFreqBracketedPrefix) {
        int count = 0;
        for (AEKey key : dbMap.keySet()) {
            if ((key.scope() + "[" + key.freq() + "]").equals(scopeFreqBracketedPrefix)) count++;
        }
        log("Type count for prefix=%s -> %d", scopeFreqBracketedPrefix, count);
        return count;
    }

    public static List<AEKeyCacheEntry> queryItemsByFrequencyCached(String scopePrefix, int freq) {
        List<AEKeyCacheEntry> result = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            if (key.scope().equals(scopePrefix) && key.freq() == freq) {
                StoredEntry stored = entry.getValue();
                AEItemKey aeKey = stored.aeKey();
                if (aeKey == null) {
                    ItemStack stack = deserializeItemStackFromBytes(key.itemBytes());
                    if (!stack.isEmpty()) {
                        aeKey = AEItemKey.of(stack);
                        dbMap.put(key, new StoredEntry(stored.count(), aeKey));
                    }
                }
                if (aeKey != null) {
                    result.add(new AEKeyCacheEntry(key, aeKey, stored.count()));
                }
            }
        }
        log("QueryItemsByFreqCached: scope=%s freq=%d entriesFound=%d", scopePrefix, freq, result.size());
        return result;
    }


    private static void rotateWalIfNeeded() throws IOException {
        if (currentWAL.length() < WAL_ROTATION_SIZE) return;
        closeWALStream();
        int suffix = 1;
        File rotated;
        do {
            rotated = new File(currentWAL.getParent(), "enderdrives.wal." + suffix++);
        } while (rotated.exists());
        Files.move(currentWAL.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING);
        openWALStream();
        replayAndDeleteWAL(rotated);
    }

    private static void replayAndDeleteWAL(File walFile) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(walFile)))) {
            while (dis.available() > 0) {
                int length = dis.readInt();
                byte[] data = new byte[length];
                dis.readFully(data);
                long storedChecksum = dis.readLong();
                if (checksum(data) != storedChecksum) continue;
                applyBinaryOperation(data);
            }
            commitDatabase();
            walFile.delete();
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

    public static void commitDatabase() {
        try {
            File temp = new File(dbFile.getAbsolutePath() + ".tmp");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
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
    }

    private static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void log(String format, Object... args) {
        if (DEBUG_LOG) System.out.printf("[EnderDB] " + format + "%n", args);
    }

    private static void migrateOldRecords() {
        List<Map.Entry<AEKey, StoredEntry>> toMigrate = new ArrayList<>();
        for (Map.Entry<AEKey, StoredEntry> entry : dbMap.entrySet()) {
            AEKey key = entry.getKey();
            String scope = key.scope();
            if (scope == null || scope.isEmpty() || scope.length() > 0 && !scope.matches("^[a-z]+_[a-z0-9\\-]+$") && !scope.equals("global")) {
                toMigrate.add(entry);
            }
        }

        if (toMigrate.isEmpty()) return;

        System.out.println("[EnderDB] Detected " + toMigrate.size() + " old-format records. Migrating to global scope...");

        for (Map.Entry<AEKey, StoredEntry> entry : toMigrate) {
            AEKey oldKey = entry.getKey();
            StoredEntry value = entry.getValue();
            AEKey newKey = new AEKey("global", oldKey.freq(), oldKey.itemBytes());
            long existing = dbMap.getOrDefault(newKey, new StoredEntry(0L, null)).count();
            dbMap.put(newKey, new StoredEntry(existing + value.count(), null));
            dbMap.remove(oldKey);
        }
        System.out.println("[EnderDB] Migration complete. Migrated " + toMigrate.size() + " entries.");
        dirty = true;
    }


    public static long getTotalItemsWritten() { return totalItemsWritten; }
    public static long getTotalCommits() { return totalCommits; }
    public static int getWalQueueSize() { return walQueue.size(); }
    public static int getDatabaseSize() { return dbMap.size(); }
    public static long getDatabaseFileSizeBytes() { return dbFile.exists() ? dbFile.length() : 0; }

}
