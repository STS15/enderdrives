package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.EnderFluidDiskItem;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class UpdateFrequencyPacket implements CustomPacketPayload {

    private final int frequency;
    private final FrequencyScope scope;
    private final int transferMode;

    public UpdateFrequencyPacket(int frequency, FrequencyScope scope, int transferMode) {
        this.frequency = frequency;
        this.scope = scope;
        this.transferMode = transferMode;
    }

    public static final Type<UpdateFrequencyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "update_frequency"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, UpdateFrequencyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UpdateFrequencyPacket::getFrequency,
            ByteBufCodecs.VAR_INT.map(FrequencyScope::fromId, FrequencyScope::getId), UpdateFrequencyPacket::getScope,
            ByteBufCodecs.VAR_INT, UpdateFrequencyPacket::getTransferMode,
            UpdateFrequencyPacket::new
    );

    private int getFrequency() {
        return frequency;
    }

    private FrequencyScope getScope() {
        return scope;
    }

    private int getTransferMode() {
        return transferMode;
    }

    public static void handle(UpdateFrequencyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack held = player.getMainHandItem();
                if (held.isEmpty()) return;

                if (held.getItem() instanceof EnderDiskItem) {
                    EnderDiskItem.setFrequency(held, packet.frequency);
                    EnderDiskItem.setScope(held, packet.scope);
                    EnderDiskItem.setTransferMode(held, packet.transferMode);
                    if (packet.scope == FrequencyScope.PERSONAL) {
                        EnderDiskItem.setOwnerUUID(held, player.getUUID());
                    }
                } else if (held.getItem() instanceof EnderFluidDiskItem) {
                    EnderFluidDiskItem.setFrequency(held, packet.frequency);
                    EnderFluidDiskItem.setScope(held, packet.scope);
                    EnderFluidDiskItem.setTransferMode(held, packet.transferMode);
                    if (packet.scope == FrequencyScope.PERSONAL) {
                        EnderFluidDiskItem.setOwnerUUID(held, player.getUUID());
                    }
                }
            }
        });
    }
}
