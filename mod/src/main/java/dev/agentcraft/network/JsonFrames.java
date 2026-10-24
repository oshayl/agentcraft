package dev.agentcraft.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Bounded ordered framing; even large snapshots fit ordinary Minecraft custom payloads. */
public final class JsonFrames {
	public static final int PART_CHARS = 6000;
	public static final int MAX_CHARS = 4 * 1024 * 1024;
	public static final int MAX_PARTS = (MAX_CHARS + PART_CHARS - 1) / PART_CHARS;
	private JsonFrames() {}

	public record Part(String id, int index, int count, String text) {
		public Part {
			if (id.length() != 36 || index < 0 || count < 1 || count > MAX_PARTS || index >= count || text.length() > PART_CHARS)
				throw new IllegalArgumentException("invalid bridge frame");
		}
	}

	public static List<Part> split(String text) {
		if (text.length() > MAX_CHARS) throw new IllegalArgumentException("bridge message too large");
		String id = UUID.randomUUID().toString();
		// Do not split surrogate pairs: writeUtf encodes each fragment independently.
		List<String> pieces = new ArrayList<>();
		for (int from = 0; from < text.length();) {
			int to = Math.min(text.length(), from + PART_CHARS);
			if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1))) to--;
			pieces.add(text.substring(from, to));
			from = to;
		}
		if (pieces.isEmpty()) pieces.add("");
		List<Part> out = new ArrayList<>();
		for (int i = 0; i < pieces.size(); i++) out.add(new Part(id, i, pieces.size(), pieces.get(i)));
		return out;
	}

	public static final class Assembler {
		private final int limit;
		private String id = "";
		private StringBuilder pending = new StringBuilder();
		private int next;
		private int count;
		private long started;
		public Assembler(int limit) { this.limit = limit; }
		public void reset() { id = ""; pending = new StringBuilder(); next = 0; count = 0; }
		public @Nullable String accept(Part p) {
			long now = System.currentTimeMillis();
			if (p.index() == 0) {
				reset(); id = p.id(); count = p.count(); started = now;
			}
			if (!p.id().equals(id) || p.index() != next || p.count() != count || now - started > 30_000
				|| (long) pending.length() + p.text().length() > limit) {
				reset(); throw new IllegalArgumentException("incomplete or oversized bridge message");
			}
			pending.append(p.text()); next++;
			if (next != count) return null;
			String text = pending.toString(); reset(); return text;
		}
	}
}
