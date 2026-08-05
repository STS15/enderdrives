package com.sts15.enderdrives.client;

import com.mojang.serialization.MapCodec;
import com.sts15.enderdrives.EnderDrives;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.ClientFluidDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.EnderFluidDiskItem;
import com.sts15.enderdrives.items.TapeDiskItem;
import com.sts15.enderdrives.network.ClientNetworkHandler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

public final class DriveStatusProperty implements RangeSelectItemModelProperty {
    public static final Identifier ID = EnderDrives.id("status");
    public static final MapCodec<DriveStatusProperty> CODEC = MapCodec.unit(DriveStatusProperty::new);
    private static final long REQUEST_COOLDOWN_MS = 1_000L;
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    @Override
    public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        if (stack.getItem() instanceof EnderDiskItem disk) {
            if (disk.isDisabled(stack) || !EnderDiskItem.isScopeBound(stack)) return 3;
            String scope = EnderDiskItem.getSafeScopePrefix(stack);
            int frequency = EnderDiskItem.getFrequency(stack);
            String cacheKey = scope + "|" + frequency;
            if (level != null) {
                request("item:" + cacheKey, () -> ClientNetworkHandler.requestDiskTypeCount(scope, frequency, disk.getTypeLimit()));
            }
            DiskTypeInfo info = ClientDiskCache.get(cacheKey);
            return status(info.typeCount(), disk.getTypeLimit());
        }
        if (stack.getItem() instanceof EnderFluidDiskItem disk) {
            if (disk.isDisabled(stack) || !EnderFluidDiskItem.isScopeBound(stack)) return 3;
            String scope = EnderFluidDiskItem.getSafeScopePrefix(stack);
            int frequency = EnderFluidDiskItem.getFrequency(stack);
            String cacheKey = scope + "|" + frequency;
            if (level != null) {
                request("fluid:" + cacheKey, () -> ClientNetworkHandler.requestFluidDiskTypeCount(scope, frequency, disk.getTypeLimit()));
            }
            FluidDiskTypeInfo info = ClientFluidDiskCache.get(cacheKey);
            return status(info.typeCount(), disk.getTypeLimit());
        }
        if (stack.getItem() instanceof TapeDiskItem disk) {
            if (TapeDiskItem.isDisabled(stack)) return 3;
            UUID tapeId = TapeDiskItem.getTapeId(stack);
            if (tapeId == null) {
                return 0;
            }
            if (level != null) {
                request("tape:" + tapeId, () -> ClientNetworkHandler.requestTapeTypeCount(tapeId));
            }
            int typeCount = ClientTapeCache.getTypeCount(tapeId);
            long byteCount = ClientTapeCache.getByteCount(tapeId);
            return tapeStatus(
                    typeCount,
                    disk.getTypeLimit(stack),
                    byteCount,
                    serverConfig.TAPE_DISK_BYTE_LIMIT.get()
            );
        }
        return 0;
    }

    private static float status(int typeCount, int typeLimit) {
        if (typeCount <= 0) {
            return 0;
        }
        if (typeLimit > 0 && typeCount >= typeLimit) {
            return 3;
        }
        return typeLimit > 0 && typeCount * 100L / typeLimit >= 75 ? 2 : 1;
    }

    private static float tapeStatus(int typeCount, int typeLimit, long byteCount, long byteLimit) {
        if (typeCount <= 0 && byteCount <= 0) {
            return 0;
        }
        long typePercent = typeLimit > 0 ? typeCount * 100L / typeLimit : 0;
        long bytePercent = byteLimit > 0 ? byteCount * 100L / byteLimit : 0;
        long percent = Math.max(typePercent, bytePercent);
        if (percent >= 100) {
            return 3;
        }
        return percent >= 75 ? 2 : 1;
    }

    private static void request(String key, Runnable request) {
        long now = System.currentTimeMillis();
        long last = LAST_REQUEST.getOrDefault(key, 0L);
        if (now - last >= REQUEST_COOLDOWN_MS) {
            LAST_REQUEST.put(key, now);
            request.run();
        }
    }

    public static void clearRequestCache() {
        LAST_REQUEST.clear();
    }

    @Override
    public MapCodec<? extends RangeSelectItemModelProperty> type() {
        return CODEC;
    }
}
