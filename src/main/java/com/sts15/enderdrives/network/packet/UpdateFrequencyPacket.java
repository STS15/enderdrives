package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.EnderFluidDiskItem;
import com.sts15.enderdrives.items.AbstractEnderDiskItem;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class UpdateFrequencyPacket implements CustomPacketPayload {

    private final int frequency;
    private final FrequencyScope scope;
    private final int transferMode;
    private final InteractionHand hand;
    private final int expectedFrequency;
    private final FrequencyScope expectedScope;
    private final int expectedTransferMode;
    private final Identifier expectedItemId;
    private final int expectedStackHash;

    public UpdateFrequencyPacket(
            int frequency,
            FrequencyScope scope,
            int transferMode,
            InteractionHand hand,
            int expectedFrequency,
            FrequencyScope expectedScope,
            int expectedTransferMode,
            Identifier expectedItemId,
            int expectedStackHash
    ) {
        this.frequency = frequency;
        this.scope = scope;
        this.transferMode = transferMode;
        this.hand = hand;
        this.expectedFrequency = expectedFrequency;
        this.expectedScope = expectedScope;
        this.expectedTransferMode = expectedTransferMode;
        this.expectedItemId = expectedItemId;
        this.expectedStackHash = expectedStackHash;
    }

    public static final Type<UpdateFrequencyPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MOD_ID, "update_frequency"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, UpdateFrequencyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UpdateFrequencyPacket::getFrequency,
            ByteBufCodecs.VAR_INT.map(FrequencyScope::fromId, FrequencyScope::getId), UpdateFrequencyPacket::getScope,
            ByteBufCodecs.VAR_INT, UpdateFrequencyPacket::getTransferMode,
            ByteBufCodecs.BOOL.map(offhand -> offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    hand -> hand == InteractionHand.OFF_HAND), packet -> packet.hand,
            ByteBufCodecs.VAR_INT, packet -> packet.expectedFrequency,
            ByteBufCodecs.VAR_INT.map(FrequencyScope::fromId, FrequencyScope::getId), packet -> packet.expectedScope,
            ByteBufCodecs.VAR_INT, packet -> packet.expectedTransferMode,
            Identifier.STREAM_CODEC, packet -> packet.expectedItemId,
            ByteBufCodecs.VAR_INT, packet -> packet.expectedStackHash,
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
                int minFrequency = Math.min(serverConfig.FREQ_MIN.get(), serverConfig.FREQ_MAX.get());
                int maxFrequency = Math.max(serverConfig.FREQ_MIN.get(), serverConfig.FREQ_MAX.get());
                if (packet.frequency < minFrequency || packet.frequency > maxFrequency) return;
                if (packet.transferMode < 0 || packet.transferMode > 2) return;
                if (!packet.scope.isEnabled()) return;

                ItemStack held = player.getItemInHand(packet.hand);
                if (held.isEmpty()) return;
                if (!(held.getItem() instanceof EnderDiskItem)
                        && !(held.getItem() instanceof EnderFluidDiskItem)) return;
                if (!BuiltInRegistries.ITEM.getKey(held.getItem()).equals(packet.expectedItemId)) return;
                if (ItemStack.hashItemAndComponents(held) != packet.expectedStackHash) return;
                if (AbstractEnderDiskItem.getFrequency(held) != packet.expectedFrequency) return;
                if (AbstractEnderDiskItem.getScope(held) != packet.expectedScope) return;
                if (AbstractEnderDiskItem.getTransferMode(held) != packet.expectedTransferMode) return;

                if (packet.scope == FrequencyScope.TEAM) {
                    if (!ModList.get().isLoaded("ftbteams")) return;
                    if (!AbstractEnderDiskItem.resolveAndCacheTeamInfo(held, player)) return;
                }

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
