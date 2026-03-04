package com.sts15.enderdrives.db;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.config.serverConfig;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;

public abstract class AbstractEnderDBManager {

    private static final BlockingQueue<byte[]> walQueue = new LinkedBlockingQueue<>();
    private static final boolean DEBUG_LOG = serverConfig.END_DB_DEBUG_LOG.get();
    private static String enderDBManager;
    private static DataOutputStream walWriter;

    public AbstractEnderDBManager(String enderDBManager, DataOutputStream walWriter) {
        AbstractEnderDBManager.enderDBManager = enderDBManager;
        AbstractEnderDBManager.walWriter = walWriter;
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
            Constants.LOG.error("Error flushing WAL queue during shutdown", e);
        }
    }

    /**
     * Logs debug messages to console if DEBUG_LOG is enabled.
     *
     * @param format The message format string.
     * @param args   Format arguments.
     */
    static void log(String format, Object... args) {
        if (DEBUG_LOG) Constants.LOG.info("[" + enderDBManager + "] " + format, args);
    }

// ==== Internal DB Tools ====

    /**
     * Calculates a checksum for a byte array using CRC32.
     *
     * @param data The byte array to checksum.
     * @return The CRC32 value.
     */
    static long checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

}
