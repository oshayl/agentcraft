package dev.agentcraft.client.library;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.LibraryItem;
import dev.agentcraft.client.foreman.Protocol.LibrarySource;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The read-only markdown folders the Foreman serves (an Obsidian vault, a docs directory): one
 * tab each in the {@link LibraryScreen}, beside memory and the artifacts.
 *
 * <p>Nothing of these is in the snapshot - a real vault is thousands of notes and tens of
 * megabytes - so this asks for what it is about to show and keeps it for the session: the index
 * (titles and paths) when a tab is first opened, and one note's text when it is selected. Notes
 * arrive as ordinary {@link MemoryEntry} values, so the reader renders them like any other note;
 * their body is empty until it has been fetched.
 *
 * <p>Everything here runs on the client thread (the futures complete there).
 */
public final class FileNotes {
	/** How many notes a tab lists at once; a query narrows, it does not page. */
	private static final int LIMIT = 200;

	private static final Map<String, List<MemoryEntry>> INDEX = new HashMap<>();
	private static final Map<String, String> QUERY = new HashMap<>();
	private static final Map<String, String> BODIES = new HashMap<>();
	private static final Set<String> LOADING = new HashSet<>();
	private static final Map<String, String> ERRORS = new HashMap<>();
	private static long revision;

	private FileNotes() {
	}

	/** Bumped whenever something arrives, so an open screen knows to lay out again. */
	public static long revision() {
		return revision;
	}

	/** The folders this Foreman serves (empty when none is configured). */
	public static List<LibrarySource> sources() {
		ForemanState s = Foreman.state();
		return s == null || s.status() == null ? List.of() : s.status().sources();
	}

	public static boolean isSource(@Nullable String scope) {
		if (scope == null || scope.isEmpty()) {
			return false;
		}
		for (LibrarySource src : sources()) {
			if (src.id().equals(scope)) {
				return true;
			}
		}
		return false;
	}

	public static String label(String sourceId) {
		for (LibrarySource src : sources()) {
			if (src.id().equals(sourceId)) {
				return src.label();
			}
		}
		return sourceId;
	}

	/** What a tab shows now, asking for the index the first time it is opened. */
	public static List<MemoryEntry> entries(String sourceId) {
		List<MemoryEntry> have = INDEX.get(sourceId);
		if (have == null) {
			requestIndex(sourceId, QUERY.get(sourceId));
			return List.of();
		}
		return have;
	}

	public static boolean loading(String sourceId) {
		return LOADING.contains(sourceId);
	}

	public static @Nullable String error(String sourceId) {
		return ERRORS.get(sourceId);
	}

	public static @Nullable String query(String sourceId) {
		return QUERY.get(sourceId);
	}

	/** Narrow a tab to the notes matching every word of {@code query} (null = everything). */
	public static void search(String sourceId, @Nullable String query) {
		String q = query == null || query.isBlank() ? null : query.strip();
		if (java.util.Objects.equals(q, QUERY.get(sourceId))) {
			return;
		}
		if (q == null) {
			QUERY.remove(sourceId);
		} else {
			QUERY.put(sourceId, q);
		}
		INDEX.remove(sourceId);
		requestIndex(sourceId, q);
	}

	/** Drop what is cached for a source, so the next look re-reads the folder. */
	public static void forget(String sourceId) {
		INDEX.remove(sourceId);
		revision++;
	}

	/** The note's text, asking for it the first time (empty until it arrives). */
	public static String body(MemoryEntry e) {
		String key = e.id();
		String have = BODIES.get(key);
		if (have != null) {
			return have;
		}
		requestBody(e.scope(), key);
		return "";
	}

	public static boolean hasBody(MemoryEntry e) {
		return BODIES.containsKey(e.id());
	}

	private static void requestIndex(String sourceId, @Nullable String query) {
		if (!LOADING.add("index:" + sourceId)) {
			return;
		}
		LOADING.add(sourceId);
		Foreman.requestLibrary(sourceId, null, query, LIMIT).whenComplete((lib, err) -> {
			LOADING.remove("index:" + sourceId);
			LOADING.remove(sourceId);
			revision++;
			if (err != null || lib == null) {
				ERRORS.put(sourceId, err == null ? "no answer" : String.valueOf(err.getMessage()));
				INDEX.put(sourceId, List.of());
				return;
			}
			if (lib.error() != null) {
				ERRORS.put(sourceId, lib.error());
				INDEX.put(sourceId, List.of());
				return;
			}
			ERRORS.remove(sourceId);
			List<MemoryEntry> out = new ArrayList<>();
			for (LibraryItem it : lib.items()) {
				out.add(toEntry(sourceId, it));
			}
			INDEX.put(sourceId, List.copyOf(out));
		});
	}

	private static void requestBody(String sourceId, String entryId) {
		if (!LOADING.add(entryId)) {
			return;
		}
		String noteId = entryId.substring(sourceId.length() + 1);
		Foreman.requestLibrary(sourceId, noteId, null, null).whenComplete((lib, err) -> {
			LOADING.remove(entryId);
			revision++;
			if (err != null || lib == null || lib.error() != null || lib.items().isEmpty()) {
				String why = err != null ? String.valueOf(err.getMessage()) : lib == null ? "no answer" : lib.error() != null ? lib.error() : "no such note";
				BODIES.put(entryId, "_Could not read this note: " + why + "_");
				return;
			}
			LibraryItem it = lib.items().get(0);
			BODIES.put(entryId, it.body() == null ? "" : it.body());
		});
	}

	/** A note as the reader wants it: scope = the source, so it lands under that tab. */
	private static MemoryEntry toEntry(String sourceId, LibraryItem it) {
		String id = sourceId + "/" + it.id();
		String body = BODIES.getOrDefault(id, "");
		return new MemoryEntry(id, sourceId, it.title(), body, it.updated(), null);
	}

	/** The folder a note sits in, for the list line ("10 Projects/Agents"). */
	public static String folderOf(MemoryEntry e) {
		String id = e.id();
		int slash = id.indexOf('/');
		String rel = slash < 0 ? id : id.substring(slash + 1);
		int last = rel.lastIndexOf('/');
		return last <= 0 ? "" : rel.substring(0, last);
	}
}
