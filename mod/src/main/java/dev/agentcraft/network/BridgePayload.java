package dev.agentcraft.network;

import dev.agentcraft.AgentCraft;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BridgePayload(JsonFrames.Part part) implements CustomPacketPayload {
	public static final Type<BridgePayload> TYPE = new Type<>(AgentCraft.id("foreman_bridge"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC = StreamCodec.of((buf, value) -> {
		var p = value.part();
		buf.writeUtf(p.id(), 36); buf.writeVarInt(p.index()); buf.writeVarInt(p.count()); buf.writeUtf(p.text(), JsonFrames.PART_CHARS);
	}, buf -> new BridgePayload(new JsonFrames.Part(buf.readUtf(36), buf.readVarInt(), buf.readVarInt(), buf.readUtf(JsonFrames.PART_CHARS))));
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}
}
