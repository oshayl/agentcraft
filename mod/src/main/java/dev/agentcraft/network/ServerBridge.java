package dev.agentcraft.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.ForemanLink;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hq.HqWorldDriver;
import dev.agentcraft.layout.Anchors;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Dedicated-server view of Foreman. Its lifetime never controls the separate job service. */
public final class ServerBridge {
	private static @Nullable ServerBridge current;
	private final MinecraftServer server;
	private final UUID owner;
	private final String version;
	private final ForemanState state;
	private final ForemanLink link;
	private final Set<UUID> subscribed = new HashSet<>();
	private final Map<UUID, JsonFrames.Assembler> inbound = new HashMap<>();
	private long rateSecond;
	private int frames;

	private ServerBridge(MinecraftServer server) throws IOException {
		this.server = server;
		if (!server.usesAuthentication()) throw new IllegalStateException("AgentCraft dedicated servers require online-mode=true");
		Properties config = new Properties();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("agentcraft-server.properties");
		if (Files.exists(file)) try (var input = Files.newInputStream(file)) { config.load(input); }
		String ownerValue = System.getenv().getOrDefault("AGENTCRAFT_OWNER_UUID", config.getProperty("ownerUuid", ""));
		owner = UUID.fromString(ownerValue);
		String tokenFile = System.getenv().getOrDefault("AGENTCRAFT_BRIDGE_TOKEN_FILE", config.getProperty("tokenFile", ""));
		String token = Files.readString(Path.of(tokenFile)).trim();
		if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("invalid private bridge credential");
		int port = Integer.parseInt(System.getenv().getOrDefault("AGENTCRAFT_PORT", config.getProperty("foremanPort", "7878")));
		if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid Foreman port");
		URI uri = URI.create("ws://127.0.0.1:" + port);
		version = FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
		state = new ForemanState(new LinkStatus(LinkStatus.Phase.WAITING_RETRY, uri.toString(), 0, null, 0, 0, false));
		link = new ForemanLink(uri, version, state, server::execute, true, null, token);
		link.observe(text -> broadcast(text));
		state.addListener(new ForemanListener() {
			@Override public void onConnection(LinkStatus status) {
				if (!status.synced()) broadcast(statusMessage());
			}
		});
	}

	public static void init() {
		BridgePayload.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!server.isDedicatedServer()) return;
			try { current = new ServerBridge(server); current.link.start(); }
			catch (Exception e) { throw new IllegalStateException("Configure ownerUuid and tokenFile in config/agentcraft-server.properties before starting AgentCraft", e); }
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (current != null && current.server == server) { current.link.stop(); current = null; }
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (current != null && current.server == server) HqWorldDriver.tick(server.overworld(), current.state);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (current == null || current.server != server) return;
			if (!handler.getPlayer().getUUID().equals(current.owner)) {
				handler.disconnect(Component.literal("This AgentCraft server is private."));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (current != null && current.server == server) {
				current.subscribed.remove(handler.getPlayer().getUUID());
				current.inbound.remove(handler.getPlayer().getUUID());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, context) -> {
			if (current != null && current.server == context.server()) current.receive(context.player(), payload.part());
		});
		Anchors.addListener(layout -> {
			if (current != null) current.server.execute(() -> { if (current != null) current.broadcast(layoutMessage()); });
		});
	}

	private void receive(ServerPlayer player, JsonFrames.Part part) {
		if (!player.getUUID().equals(owner)) return;
		long second = System.currentTimeMillis() / 1000;
		if (second != rateSecond) { rateSecond = second; frames = 0; }
		if (++frames > 128) { player.connection.disconnect(Component.literal("AgentCraft request rate exceeded.")); return; }
		try {
			String text = inbound.computeIfAbsent(player.getUUID(), id -> new JsonFrames.Assembler(65_536)).accept(part);
			if (text == null) return;
			JsonObject message = JsonParser.parseString(text).getAsJsonObject();
			String type = message.get("type").getAsString();
			if (type.equals("bridge.hello")) {
				if (message.get("protocol").getAsInt() != Protocol.VERSION || !version.equals(message.get("modVersion").getAsString())) {
					player.connection.disconnect(Component.literal("Install the matching AgentCraft client release.")); return;
				}
				subscribed.add(owner);
				send(player, layoutMessage());
				send(player, state.link().synced() && state.hasData() ? state.snapshotJson().toString() : statusMessage());
				return;
			}
			if (!subscribed.contains(owner)) throw new IllegalArgumentException("bridge handshake required");
			// Development process-control commands do not belong on an installed server.
			if (type.equals("dev.action") || type.equals("hello")) throw new IllegalArgumentException("unsupported installed-server action");
			String id = message.get("id").getAsString();
			if (id.isEmpty() || id.length() > 200) throw new IllegalArgumentException("invalid request id");
			if (!link.status().synced()) { refusal(player, id, "Foreman offline; refresh state before retrying"); return; }
			// The owner identity comes from authenticated Minecraft, never from message fields.
			link.send(message).exceptionally(error -> { server.execute(() -> refusal(player, id, "Connection lost or outcome unknown; refresh state before retrying")); return null; });
		} catch (Exception e) {
			player.connection.disconnect(Component.literal("Invalid AgentCraft bridge message."));
		}
	}

	private String statusMessage() {
		JsonObject message = new JsonObject(); message.addProperty("type", "bridge.status");
		message.addProperty("message", "Foreman unavailable; jobs remain owned by the job service");
		return message.toString();
	}
	private static String layoutMessage() {
		JsonObject out = Anchors.toJson(Anchors.current()); out.addProperty("type", "bridge.layout"); return out.toString();
	}
	private void refusal(ServerPlayer player, String id, String reason) {
		JsonObject out = new JsonObject(); out.addProperty("type", "ack"); out.addProperty("re", id); out.addProperty("ok", false); out.addProperty("error", reason); send(player, out.toString());
	}
	private void broadcast(String text) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) if (subscribed.contains(player.getUUID())) send(player, text);
	}
	private static void send(ServerPlayer player, String text) {
		if (!ServerPlayNetworking.canSend(player, BridgePayload.TYPE)) return;
		for (var part : JsonFrames.split(text)) ServerPlayNetworking.send(player, new BridgePayload(part));
	}
}
