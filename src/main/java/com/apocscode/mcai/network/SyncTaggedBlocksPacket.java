package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: Sync the companion's tagged logistics blocks so the client
 * can render colored outlines when the player holds the Logistics Wand.
 */
public record SyncTaggedBlocksPacket(int entityId, List<TaggedBlock> blocks) implements CustomPacketPayload {
    public static final Type<SyncTaggedBlocksPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "sync_tagged_blocks"));

    public static final StreamCodec<FriendlyByteBuf, SyncTaggedBlocksPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SyncTaggedBlocksPacket decode(FriendlyByteBuf buf) {
                    int entityId = buf.readInt();
                    int count = buf.readVarInt();
                    List<TaggedBlock> blocks = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        long posLong = buf.readLong();
                        int roleOrd = buf.readVarInt();
                        TaggedBlock.Role[] roles = TaggedBlock.Role.values();
                        TaggedBlock.Role role = (roleOrd >= 0 && roleOrd < roles.length)
                                ? roles[roleOrd] : TaggedBlock.Role.STORAGE;
                        blocks.add(new TaggedBlock(BlockPos.of(posLong), role));
                    }
                    return new SyncTaggedBlocksPacket(entityId, blocks);
                }

                @Override
                public void encode(FriendlyByteBuf buf, SyncTaggedBlocksPacket packet) {
                    buf.writeInt(packet.entityId());
                    buf.writeVarInt(packet.blocks().size());
                    for (TaggedBlock tb : packet.blocks()) {
                        buf.writeLong(tb.pos().asLong());
                        buf.writeVarInt(tb.role().ordinal());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncTaggedBlocksPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Entity entity = mc.level.getEntity(packet.entityId());
                if (entity instanceof CompanionEntity companion) {
                    companion.setTaggedBlocks(packet.blocks());
                }
            }
        });
    }
}
