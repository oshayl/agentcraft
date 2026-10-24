package dev.agentcraft.network;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.ForemanLink;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Exercises real framing and transport code without starting a game or logging into a backend. */
public final class BridgeChecks {
	private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
	public static void main(String[] args) throws Exception {
		String text = "x".repeat(5999) + "\uD83D\uDE80" + "y".repeat(12000);
		var frames = JsonFrames.split(text);
		var receiver = new JsonFrames.Assembler(JsonFrames.MAX_CHARS);
		String result = null;
		for (var frame : frames) {
			check(frame.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32767, "payload byte limit");
			result = receiver.accept(frame);
		}
		check(text.equals(result), "unicode snapshot survives fragmentation");
		try { receiver.accept(frames.get(1)); throw new AssertionError("out of order accepted"); }
		catch (IllegalArgumentException expected) {}
		try { new JsonFrames.Assembler(100).accept(frames.getFirst()); throw new AssertionError("oversized request accepted"); }
		catch (IllegalArgumentException expected) {}

		var state = new ForemanState(new LinkStatus(LinkStatus.Phase.WAITING_RETRY, "minecraft://test", 0, null, 0, 0, false));
		List<String> sent = new ArrayList<>();
		var link = new ForemanLink(URI.create("minecraft://test"), "test", state, Runnable::run, true, sent::add, null);
		link.start();
		link.receive("{\"type\":\"snapshot\",\"agents\":[],\"tasks\":[],\"decisions\":[],\"repos\":[]}");
		check(link.status().synced() && state.hasData(), "snapshot completes handshake");
		link.receive("{\"type\":\"agent.upsert\",\"agent\":{\"id\":\"worker\",\"name\":\"Worker\",\"state\":\"running\"}}");
		var joined = new ForemanState(state.link());
		joined.receive("snapshot", state.snapshotJson());
		check(joined.agent("worker") != null && joined.agent("worker").state().wire().equals("running"), "late join snapshot preserves wire enum values");
		JsonObject message = new JsonObject(); message.addProperty("type", "permissions.set"); message.addProperty("mode", "ask");
		var ack = link.send(message);
		String id = message.get("id").getAsString();
		link.receive("{\"type\":\"ack\",\"re\":\"" + id + "\",\"ok\":true}");
		check(ack.get().ok(), "relay resolves request acknowledgement");
		var library = link.requestLibrary("test", null, null, null);
		link.remoteUnavailable("test disconnect");
		check(library.isCompletedExceptionally(), "disconnect fails library waiters immediately");
		check(state.isStale(), "offline status marks data stale");
		int snapshots = state.snapshotCount();
		link.stop();
		link.receive("{\"type\":\"snapshot\"}");
		check(state.snapshotCount() == snapshots, "stopped transport cannot overwrite replacement state");
		state.clearData();
		check(!state.hasData() && state.agents().isEmpty(), "world switch clears old data");
		System.out.println("Bridge checks passed: Unicode framing, bounds, handshake, acknowledgements, disconnects and session isolation");
	}
}
