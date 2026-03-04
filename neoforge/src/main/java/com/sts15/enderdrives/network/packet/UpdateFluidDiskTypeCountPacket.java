package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.db.ClientFluidDiskCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client cache update for fluid disks (mirrors the item UpdateDiskTypeCountPacket).
 * totalAmount is in mB.
 */
public record UpdateFluidDiskTypeCountPacket(
        String scopePrefix,
        int frequency,
        int typeCount,
        int typeLimit,
        long totalAmount,
        List<FluidStack> topFluids
) implements CustomPacketPayload {

    public static final Type<UpdateFluidDiskTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_fluid_disk_type_count"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateFluidDiskTypeCountPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,                                              UpdateFluidDiskTypeCountPacket::scopePrefix,
            ByteBufCodecs.VAR_INT,                                                  UpdateFluidDiskTypeCountPacket::frequency,
            ByteBufCodecs.VAR_INT,                                                  UpdateFluidDiskTypeCountPacket::typeCount,
            ByteBufCodecs.VAR_INT,                                                  UpdateFluidDiskTypeCountPacket::typeLimit,
            ByteBufCodecs.VAR_LONG,                                                 UpdateFluidDiskTypeCountPacket::totalAmount,
            FluidStack.STREAM_CODEC.apply(ByteBufCodecs.list()),                    UpdateFluidDiskTypeCountPacket::topFluids,
            UpdateFluidDiskTypeCountPacket::new
    );

    public static void handle(UpdateFluidDiskTypeCountPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            String key = packet.scopePrefix() + "|" + packet.frequency();
            // Reuse your existing client cache structure; values are generic (counts + list)
            ClientFluidDiskCache.update(
                    key,
                    packet.typeCount(),
                    packet.typeLimit(),
                    packet.totalAmount(),   // interpret as "total amount" for fluids (mB)
                    packet.topFluids()      // you can choose to show names/volumes client-side
            );
        });
    }
}
