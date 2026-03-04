package com.sts15.enderdrives.network.packet;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.db.EnderFluidDBManager;
import com.sts15.enderdrives.db.FluidKeyCacheEntry;
import com.sts15.enderdrives.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RequestFluidDiskTypeCountPacket implements CustomPacketPayload {

    public static final Type<RequestFluidDiskTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_fluid_disk_type_count"));

    private final String scopePrefix;
    private final int frequency;
    private final int typeLimit;

    public RequestFluidDiskTypeCountPacket(String scopePrefix, int frequency, int typeLimit) {
        this.scopePrefix = scopePrefix;
        this.frequency = frequency;
        this.typeLimit = typeLimit;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, RequestFluidDiskTypeCountPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, p -> p.scopePrefix,
            ByteBufCodecs.VAR_INT,      p -> p.frequency,
            ByteBufCodecs.VAR_INT,      p -> p.typeLimit,
            RequestFluidDiskTypeCountPacket::new
    );

    public static void handle(RequestFluidDiskTypeCountPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            int typeCount    = EnderFluidDBManager.getTypeCountInclusive(packet.scopePrefix, packet.frequency);
            long totalMilliB = EnderFluidDBManager.getTotalAmountInclusive(packet.scopePrefix, packet.frequency);

            // Build top N fluids safely (guard the cast; ignore non-fluid keys just in case)
            List<FluidStack> topFluids = EnderFluidDBManager
                    .queryFluidsByFrequency(packet.scopePrefix, packet.frequency)
                    .stream()
                    .sorted(Comparator.comparingLong(FluidKeyCacheEntry::count).reversed())
                    .limit(5)
                    .map(e -> {
                        long amt = Math.min(e.count(), Integer.MAX_VALUE);
                        AEKey k = e.aeKey(); // declared type may be AEKey
                        if (k instanceof AEFluidKey fk) {
                            return fk.toStack((int) amt);
                        }
                        return FluidStack.EMPTY;
                    })
                    .filter(fs -> !fs.isEmpty())
                    .collect(Collectors.toList());

            if (ctx.player() instanceof ServerPlayer sp) {
                NetworkHandler.sendToClient(
                        sp,
                        new UpdateFluidDiskTypeCountPacket(
                                packet.scopePrefix,
                                packet.frequency,
                                typeCount,
                                packet.typeLimit,
                                totalMilliB,
                                topFluids
                        )
                );
            }
        });
    }
}
