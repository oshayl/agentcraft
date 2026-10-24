package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.network.BridgePayload;
import dev.agentcraft.network.JsonFrames;
import java.net.URI;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Switches only the view transport. Disconnecting never sends a stop to the job service. */
public final class ClientBridge {
	private static final JsonFrames.Assembler inbound = new JsonFrames.Assembler(JsonFrames.MAX_CHARS);
	private static boolean remote;
	private ClientBridge() {}

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if (mc.getSingleplayerServer() != null) return;
			remote = true;
			Foreman.link().stop();
			Foreman.state().clearData();
			Anchors.clearRemote(); inbound.reset();
			var link = new ForemanLink(URI.create("minecraft://agentcraft"), version(), Foreman.state(), mc::execute, true,
				ClientBridge::send, null);
			Foreman.install(Foreman.state(), link);
			link.start();
			if (ClientPlayNetworking.canSend(BridgePayload.TYPE)) hello();
			else link.remoteUnavailable("This server does not provide the AgentCraft bridge");
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			if (!remote) return;
			remote = false;
			Foreman.link().stop();
			Foreman.state().clearData();
			Anchors.clearRemote(); inbound.reset();
			URI uri = URI.create("ws://127.0.0.1:" + ClientEnv.intValue("AGENTCRAFT_PORT", 7878));
			var link = new ForemanLink(uri, version(), Foreman.state(), mc::execute, ClientEnv.flag("AGENTCRAFT_FOREMAN", true));
			Foreman.install(Foreman.state(), link);
			link.start();
		});
		ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, context) -> {
			if (!remote) return;
			try {
				String text = inbound.accept(payload.part());
				if (text == null) return;
				JsonObject message = JsonParser.parseString(text).getAsJsonObject();
				String type = message.get("type").getAsString();
				if (type.equals("bridge.layout")) Anchors.receiveRemote(message);
				else if (type.equals("bridge.status")) Foreman.link().remoteUnavailable("Foreman unavailable on the server");
				else Foreman.link().receive(text);
			} catch (Exception e) {
				inbound.reset();
				Foreman.link().remoteUnavailable("Invalid or incomplete server state; reconnect to refresh");
				AgentCraft.LOGGER.warn("Rejected incomplete AgentCraft bridge frame");
			}
		});
	}
	private static String version() {
		return FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
	}
	private static void hello() {
		JsonObject hello = new JsonObject(); hello.addProperty("type", "bridge.hello");
		hello.addProperty("protocol", Protocol.VERSION); hello.addProperty("modVersion", version()); send(hello.toString());
	}
	private static void send(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (!remote || mc.getConnection() == null || !ClientPlayNetworking.canSend(BridgePayload.TYPE)) throw new IllegalStateException("AgentCraft server unavailable");
		for (var part : JsonFrames.split(text)) ClientPlayNetworking.send(new BridgePayload(part));
	}
}
