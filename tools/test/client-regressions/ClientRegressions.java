import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.library.FileNotes;
import dev.agentcraft.client.library.LibraryScreen;
import dev.agentcraft.client.goals.GoalScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** Behavioral regression coverage for client bugs found during the orchestration review. */
public final class ClientRegressions {
	private static int checks;

	public static void main(String[] args) throws Exception {
		if (args.length > 0) {
			for (String test : args) ClientRegressions.class.getDeclaredMethod(test).invoke(null);
			System.out.println("Client regressions passed: " + checks + " assertions");
			return;
		}
		latestSearchWins();
		returningToOriginalQueryStillRefreshes();
		forgetInvalidatesAnInflightResponse();
		staleErrorsDoNotPoisonNewSearches();
		copyUsesFetchedBodyAndWaitsForLoading();
		goalActionsDoNotDependOnRenderFrames();
		goalSelectionRevealsActions();
		goalHitTargetsStayWithinScissor();
		System.out.println("Client regressions passed: " + checks + " assertions");
	}

	private static void check(boolean condition, String message) {
		checks++;
		if (!condition) throw new AssertionError(message);
	}

	private static Protocol.Library library(String source, String title, String body) {
		return new Protocol.Library(null, source,
			List.of(new Protocol.LibraryItem(title + ".md", title, "", 0, 1, body)), 1, false, null);
	}

	private static Foreman.Request request() {
		return Foreman.requests.getLast();
	}

	private static void latestSearchWins() {
		FileNotes.search("search", "old");
		Foreman.Request old = request();
		FileNotes.search("search", "new");
		check(request() == old, "query changes should coalesce while an index is in flight");
		old.future().complete(library("search", "Old", null));
		check(request() != old && "new".equals(request().query()), "latest search must be requested after stale completion");
		check(FileNotes.entries("search").isEmpty(), "stale rows must not be published under the new query");
		check(FileNotes.loading("search"), "loading must remain true while latest request runs");
		request().future().complete(library("search", "New", null));
		check("New".equals(FileNotes.entries("search").getFirst().title()), "latest query must populate the cache");
		check(!FileNotes.loading("search"), "latest completion must clear loading");
	}

	private static void returningToOriginalQueryStillRefreshes() {
		FileNotes.search("aba", "a");
		Foreman.Request first = request();
		FileNotes.search("aba", "b");
		FileNotes.search("aba", "a");
		first.future().complete(library("aba", "StaleA", null));
		check(request() != first && "a".equals(request().query()), "A-B-A query changes must invalidate the first A request");
		request().future().complete(library("aba", "FreshA", null));
		check("FreshA".equals(FileNotes.entries("aba").getFirst().title()), "A-B-A must publish the refreshed A result");
	}

	private static void forgetInvalidatesAnInflightResponse() {
		FileNotes.entries("forget");
		Foreman.Request first = request();
		FileNotes.forget("forget");
		first.future().complete(library("forget", "Stale", null));
		check(request() != first, "forget during loading must cause a new request");
		check(FileNotes.entries("forget").isEmpty(), "forgotten response must not repopulate cache");
		request().future().complete(library("forget", "Fresh", null));
		check("Fresh".equals(FileNotes.entries("forget").getFirst().title()), "refreshed index must become available");
		FileNotes.forget("forget");
		FileNotes.entries("forget");
		check(!request().future().isDone(), "forget after loading must fetch again on next access");
		request().future().complete(library("forget", "Refreshed", null));
	}

	private static void staleErrorsDoNotPoisonNewSearches() {
		FileNotes.search("errors", "old");
		Foreman.Request first = request();
		FileNotes.search("errors", "new");
		first.future().completeExceptionally(new IllegalStateException("old failure"));
		check(FileNotes.error("errors") == null, "an obsolete request's failure must not be shown for the new query");
		request().future().completeExceptionally(new IllegalStateException("new failure"));
		check("new failure".equals(FileNotes.error("errors")), "current failure must still be reported");
		FileNotes.search("errors", null);
		check(FileNotes.error("errors") == null && request().query() == null, "new search must clear old errors and normalize blank query");
		request().future().complete(library("errors", "All", null));
	}

	private static void copyUsesFetchedBodyAndWaitsForLoading() throws Exception {
		Foreman.STATE.sources.add(new Protocol.LibrarySource("copy", "Vault"));
		Protocol.MemoryEntry entry = new Protocol.MemoryEntry("copy/note.md", "copy", "Note", "", 0, null);
		LibraryScreen screen = new LibraryScreen("copy", entry.id());
		field(screen, "entries").set(screen, List.of(entry));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_C));
		check("unchanged".equals(screen.minecraft.keyboardHandler.clipboard), "copy while loading must preserve clipboard");
		check(((String) field(screen, "flash").get(screen)).contains("Reading"), "copy while loading must explain what to do");
		request().future().complete(new Protocol.Library(null, "copy", List.of(
			new Protocol.LibraryItem("note.md", "Note", "", 0, 12, "# Actual note\nBody")), 1, false, null));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_C));
		check("# Actual note\nBody".equals(screen.minecraft.keyboardHandler.clipboard), "copy must use the cached body displayed by reader");
		check(entry.body().isEmpty(), "test must exercise the original empty index entry");
		Protocol.MemoryEntry memory = new Protocol.MemoryEntry("memory", "shared", "Memory", "ordinary memory", 0, null);
		LibraryScreen ordinary = new LibraryScreen("shared", memory.id());
		field(ordinary, "entries").set(ordinary, List.of(memory));
		ordinary.keyPressed(new KeyEvent(InputConstants.KEY_C));
		check("ordinary memory".equals(ordinary.minecraft.keyboardHandler.clipboard), "ordinary memory copy must keep working");
	}

	private static void goals() {
		Foreman.STATE.goals.clear();
		Foreman.actions.clear();
		Foreman.online = true;
		for (int i = 0; i < 16; i++) {
			Foreman.STATE.goals.put("g" + i, new Protocol.Goal("g" + i, "Goal " + i, 0,
				i == 15 ? GoalStatus.DONE : GoalStatus.ACTIVE, null, 0, 0));
		}
	}

	private static void goalActionsDoNotDependOnRenderFrames() {
		goals();
		GoalScreen screen = new GoalScreen(null);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_1 + 8));
		check("g7".equals(screen.selected()), "number key must select offscreen goal");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_S));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN));
		check(Foreman.actions.size() == 1 && Foreman.actions.getFirst().equals(new Foreman.Action("cancel", "g7")),
			"pick and confirm before another frame must stop the newly selected goal");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_1));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_R));
		screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN));
		check(Foreman.actions.getLast().equals(new Foreman.Action("remove", "g15")), "finished goal must be removable from keyboard");
		Foreman.online = false;
		screen.press("clear");
		screen.press("clear");
		check(Foreman.actions.size() == 2, "offline action must not send");
	}

	private static void goalSelectionRevealsActions() throws Exception {
		goals();
		GoalScreen screen = new GoalScreen(null);
		frame(screen);
		for (int i = 0; i < 12; i++) screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN));
		GuiGraphicsExtractor g = frame(screen);
		int[] viewport = g.scissors.getFirst();
		Object stop = button(screen, "stop");
		check(stop != null, "keyboard selection must render action buttons for far-away goals");
		check(number(stop, "h") == 20 && number(stop, "y") >= viewport[1] && number(stop, "y") + 20 <= viewport[3],
			"selected goal's full action buttons must be visible");
		screen.keyPressed(new KeyEvent(InputConstants.KEY_UP));
		frame(screen);
		check(button(screen, "stop") != null, "up navigation must keep actions visible");
		GoalScreen shortScreen = new GoalScreen("g0");
		shortScreen.height = 180;
		frame(shortScreen);
		check(number(button(shortScreen, "stop"), "h") == 20, "short viewport must prioritize full action buttons");
	}

	private static void goalHitTargetsStayWithinScissor() throws Exception {
		goals();
		GoalScreen screen = new GoalScreen("g14");
		frame(screen);
		for (int offset = 1; offset < 80; offset++) {
			field(screen, "scroll").setFloat(screen, offset);
			GuiGraphicsExtractor g = frame(screen);
			int[] viewport = g.scissors.getFirst();
			for (Object b : (List<?>) field(screen, "buttons").get(screen)) {
				if (value(b, "id").equals("clear")) continue;
				check(number(b, "y") >= viewport[1] && number(b, "y") + number(b, "h") <= viewport[3],
					"every row/action hitbox must fit the viewport at scroll " + offset);
			}
			Object clear = button(screen, "clear");
			screen.mouseClicked(new MouseButtonEvent(number(clear, "x") + 1, number(clear, "y") + 1, 0), false);
			check(screen.lastClick().contains("-> clear"), "scrolled row must not intercept Clear finished");
			screen.mouseClicked(new MouseButtonEvent(viewport[0] + 10, viewport[3] + 1, 0), false);
			check(screen.lastClick().contains("-> (nothing)"), "hidden action or row must not intercept footer clicks");
		}
	}

	private static GuiGraphicsExtractor frame(GoalScreen screen) {
		GuiGraphicsExtractor g = new GuiGraphicsExtractor();
		screen.extractRenderState(g, -1, -1, 0);
		return g;
	}

	private static Field field(Object object, String name) throws Exception {
		Field f = object.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static Object value(Object record, String name) throws Exception {
		Method method = record.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(record);
	}

	private static int number(Object record, String name) throws Exception {
		return (Integer) value(record, name);
	}

	private static Object button(GoalScreen screen, String id) throws Exception {
		for (Object b : (List<?>) field(screen, "buttons").get(screen)) {
			if (id.equals(value(b, "id"))) return b;
		}
		return null;
	}
}
