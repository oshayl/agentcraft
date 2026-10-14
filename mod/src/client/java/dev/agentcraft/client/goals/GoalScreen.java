package dev.agentcraft.client.goals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The goal queue (right-click the Goal Atrium's plinth, or {@code /goals}): every goal the team
 * has been given, newest first, with its repo, how far through it is and how much work is still
 * live under it. Click one to open its actions — Stop goal (it and every unfinished task under
 * it) and Remove (a finished goal and its tasks, off the board for good), both through
 * {@code Foreman.goalAction}, with the Foreman's answer shown in place.
 *
 * <p>Paper panel, ink text, one clay primary action, like the task card. Shootable as
 * {@code dev.screen {open:"goals"}}.
 */
public class GoalScreen extends Screen {
	private static final int W = 352;
	/** A collapsed row: id + status, the goal's text, the progress bar and its tally. */
	private static final int ROW_H = 38;
	/** What the selected row adds: the live-task line and the buttons. */
	private static final int OPEN_H = 44;
	private static final int LIST_MIN = ROW_H * 2;
	static final long CONFIRM_NANOS = 5_000_000_000L;

	private @Nullable String selected;
	private @Nullable String feedback;
	private boolean feedbackError;
	private boolean sending;
	private long requestGeneration;
	/** The armed destructive action ("cancel"/"remove"/"clear") and when it lapses. */
	private @Nullable String confirm;
	private long confirmUntil;
	private float scroll;
	private float maxScroll;
	private final List<Btn> buttons = new ArrayList<>();
	/** The last click the screen was handed and where it landed, and how many it has had, for {@code dev.goals}. */
	private @Nullable String lastClick;
	private int clicks;

	private record Btn(String id, String label, int x, int y, int w, int h, boolean primary, boolean enabled) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** One goal with the tally the row shows, so the counting happens once per frame. */
	private record Row(Goal goal, int done, int live, int stopped) {
		int total() {
			return done + live + stopped;
		}

		boolean finished() {
			return goal.status() == GoalStatus.DONE || goal.status() == GoalStatus.CANCELLED || goal.status() == GoalStatus.FAILED;
		}
	}

	public GoalScreen(@Nullable String goalId) {
		super(Component.literal("Goals"));
		this.selected = goalId;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** The queue, newest first: the goal you just gave the team is the one you came to look at. */
	private List<Row> rows() {
		ForemanState s = Foreman.state();
		List<Row> out = new ArrayList<>();
		if (s == null) {
			return out;
		}
		List<Goal> goals = new ArrayList<>(s.goals().values());
		for (int i = goals.size() - 1; i >= 0; i--) {
			Goal g = goals.get(i);
			int done = 0, live = 0, stopped = 0;
			for (Task t : s.tasks().values()) {
				if (!g.id().equals(t.goalId())) {
					continue;
				}
				if (t.status() == TaskStatus.DONE) {
					done++;
				} else if (t.status() == TaskStatus.CANCELLED) {
					stopped++;
				} else {
					live++;
				}
			}
			out.add(new Row(g, done, live, stopped));
		}
		return out;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractTransparentBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		buttons.clear();
		if (confirm != null && System.nanoTime() > confirmUntil) {
			confirm = null;
		}
		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		int error = UiStyle.color("paper.del_fg", 0xFF873C2A);
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = W - pad.left() - pad.right();
		List<Row> rows = rows();
		boolean live = Foreman.connected();
		String note = feedbackLine(rows, live);
		List<FormattedCharSequence> noteLines = note == null ? List.of() : clip(TextUtil.wrap(font, note, inner), 3);

		// ---- measure: the list takes what is left after the header, the footer and the note
		int chrome = pad.top() + 14 + 6 + 3 + 6 + 12 + pad.bottom() + (noteLines.isEmpty() ? 0 : noteLines.size() * 10 + 6);
		int content = 0;
		for (Row r : rows) {
			content += ROW_H + (r.goal().id().equals(selected) ? OPEN_H : 0);
		}
		if (rows.isEmpty()) {
			content = 24;
		}
		int listH = Math.max(LIST_MIN, Math.min(content, height - 40 - chrome));
		int h = chrome + listH;
		int px = (width - W) / 2;
		int py = Math.max(4, (height - h) / 2);
		maxScroll = Math.max(0, content - listH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();

		// ---- header: the count, and the one action that is about the whole queue
		Panels.sprite(g, Kit.HEADER, x, y, inner, 14);
		int finished = 0, running = 0;
		for (Row r : rows) {
			if (r.finished()) {
				finished++;
			} else {
				running++;
			}
		}
		String count = rows.isEmpty() ? "no goals yet" : running + " going, " + finished + " finished";
		Panels.text(g, font, "Goals", x + 6, y + 3, ink);
		Panels.text(g, font, count, x + 6 + font.width("Goals") + 8, y + 3, muted);
		String clearLabel = "clear".equals(confirm) ? "Confirm clear" : "Clear finished";
		int cw = font.width(clearLabel) + 12;
		button(g, "clear", clearLabel, x + inner - cw, y, cw, 14, "clear".equals(confirm), live && !sending && finished > 0, mouseX, mouseY, true);
		y += 14 + 6;

		// ---- the queue
		int top = y;
		g.enableScissor(x, top, x + inner, top + listH);
		if (rows.isEmpty()) {
			Panels.text(g, font, Foreman.state() != null && Foreman.state().hasData() ? "Press ` and type a goal." : "Waiting for the Foreman.", x + 2,
				top + 6, muted);
		}
		int ry = top - Math.round(scroll);
		for (Row r : rows) {
			boolean open = r.goal().id().equals(selected);
			int rh = ROW_H + (open ? OPEN_H : 0);
			if (ry + rh >= top && ry <= top + listH) {
				drawRow(g, r, open, x, ry, inner, top, listH, ink, muted, error, live, mouseX, mouseY);
			}
			ry += rh;
		}
		g.disableScissor();
		y = top + listH;

		// ---- footer
		Panels.divider(g, x, y, inner);
		y += 3 + 6;
		for (FormattedCharSequence l : noteLines) {
			g.text(font, l, x, y, feedbackError ? error : confirm != null ? ink : muted, false);
			y += 10;
		}
		if (!noteLines.isEmpty()) {
			y += 6;
		}
		int kx = keycap(g, "Esc", "close", x, y);
		if (maxScroll > 0) {
			Panels.text(g, font, "Scroll for more", kx + 10, y, muted);
		}
	}

	private void drawRow(GuiGraphicsExtractor g, Row r, boolean open, int x, int y, int inner, int top, int listH, int ink, int muted, int error,
		boolean live, int mx, int my) {
		Goal gl = r.goal();
		boolean hover = mx >= x && mx < x + inner && my >= y && my < y + ROW_H && my >= top && my < top + listH;
		if (open) {
			g.fill(x, y, x + inner, y + ROW_H + OPEN_H - 2, UiStyle.color("palette.ui.panel_hi", 0xFFFFFBF4));
			g.fill(x, y, x + 2, y + ROW_H + OPEN_H - 2, UiStyle.CLAY);
		} else if (hover) {
			g.fill(x, y, x + inner, y + ROW_H - 2, UiStyle.color("palette.ui.panel_hi", 0xFFFFFBF4));
		}
		int tx = x + 7;
		Panels.dot(g, family(gl), tx, y + 3, false);
		Panels.text(g, font, gl.id(), tx + 11, y + 2, muted);
		int sx = tx + 11 + font.width(gl.id()) + 6;
		Panels.text(g, font, statusLabel(gl), sx, y + 2, ink);
		String repo = repoName(gl.repoId());
		if (!repo.isEmpty()) {
			Panels.text(g, font, repo, x + inner - 4 - font.width(repo), y + 2, UiStyle.color("paper.path", 0xFF6C5415));
		}
		Panels.text(g, font, TextUtil.ellipsize(font, gl.text().replace("`", "").strip(), inner - 14), tx, y + 13, ink);
		// the bar is the Foreman's own progress; the tally underneath is what it is made of
		Panels.progress(g, tx, y + 26, 96, gl.progress(), tint(gl));
		String tally = r.total() == 0 ? "no tasks yet"
			: r.done() + "/" + r.total() + " done" + (r.live() > 0 ? " · " + r.live() + " live" : "") + (r.stopped() > 0 ? " · " + r.stopped() + " stopped" : "");
		Panels.text(g, font, tally, tx + 104, y + 24, muted);
		// the painted highlight stops two pixels short to separate the rows; the hit box does not,
		// so there is no dead strip between them to click into
		buttons.add(new Btn("open:" + gl.id(), gl.id(), x, y, inner, ROW_H, false, true));
		if (!open) {
			return;
		}
		int by = y + ROW_H;
		// Removal takes the tasks with it, so say how many before the button is pressed, not after.
		String what = r.live() > 0
			? r.live() + " task(s) still going; stopping the goal stops them too"
			: r.total() == 0 ? "nothing has been planned for it yet" : "nothing is running on it; " + r.total() + " task(s) would be removed with it";
		Panels.text(g, font, TextUtil.ellipsize(font, what, inner - 14), tx, by, muted);
		by += 12;
		int gap = 4;
		int bw = (inner - 14 - gap) / 2;
		boolean armedStop = "cancel".equals(confirm) && gl.id().equals(selected);
		boolean armedRemove = "remove".equals(confirm) && gl.id().equals(selected);
		// a goal is stoppable while anything under it is unfinished, or while it is not finished itself
		boolean stoppable = r.live() > 0 || !r.finished();
		button(g, "stop", armedStop ? "Confirm stop" : "Stop goal", tx, by, bw, 20, armedStop, live && !sending && stoppable, mx, my, false);
		button(g, "remove", armedRemove ? "Confirm remove" : "Remove", tx + bw + gap, by, inner - 14 - bw - gap, 20, armedRemove,
			live && !sending && r.finished() && r.live() == 0, mx, my, false);
	}

	/** The line under the divider: the confirmation prompt, the Foreman's answer, or why a button is off. */
	private @Nullable String feedbackLine(List<Row> rows, boolean live) {
		if ("clear".equals(confirm)) {
			return "Take every finished goal off the board, with its tasks? Press Confirm clear. (Esc just closes this.)";
		}
		Row sel = null;
		for (Row r : rows) {
			if (r.goal().id().equals(selected)) {
				sel = r;
			}
		}
		if (sel != null && "cancel".equals(confirm)) {
			return "Stop " + sel.goal().id() + (sel.live() > 0 ? " and its " + sel.live() + " unfinished task(s)" : "")
				+ "? Press Confirm stop. The finished work stays on the board. (Esc just closes this.)";
		}
		if (sel != null && "remove".equals(confirm)) {
			return "Remove " + sel.goal().id() + " and its " + sel.total() + " task(s) for good? Press Confirm remove. (Esc just closes this.)";
		}
		if (feedback != null) {
			return feedback;
		}
		if (!live) {
			return "Foreman offline: actions are disabled";
		}
		if (sel == null) {
			return rows.isEmpty() ? null : "Click a goal for its actions.";
		}
		if (!sel.finished() && sel.live() == 0 && sel.total() > 0) {
			return sel.goal().id() + " has no work left to stop; it finishes as soon as the Foreman catches up.";
		}
		if (!sel.finished()) {
			return "Stop it to take the work off the team; Remove needs it stopped or finished first.";
		}
		return null;
	}

	private int keycap(GuiGraphicsExtractor g, String key, String verb, int x, int y) {
		int kw = font.width(key) + 8;
		Panels.sprite(g, Kit.KEYCAP, x, y - 2, kw, 12);
		Panels.text(g, font, key, x + 4, y, UiStyle.color("paper.text", 0xFF1F1E1D));
		Panels.text(g, font, verb, x + kw + 4, y, UiStyle.color("paper.muted", 0xFF655E55));
		return x + kw + 4 + font.width(verb);
	}

	private void button(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, int h, boolean primary, boolean enabled, int mx, int my,
		boolean small) {
		Btn b = new Btn(id, label, x, y, w, h, primary, enabled);
		buttons.add(b);
		boolean hover = enabled && b.hit(mx, my);
		if (small) {
			Panels.sprite(g, Kit.button(primary, enabled ? hover ? "hover" : "normal" : "disabled"), x, y, w, h);
			Panels.text(g, font, label, x + (w - font.width(label)) / 2, y + 3,
				primary ? UiStyle.color("palette.ui.panel_hi", 0xFFFFFBF4) : UiStyle.color("paper.text", 0xFF1F1E1D));
			return;
		}
		if (primary && enabled) {
			// Panels.button paints a primary label in the highlight colour, which is the clay the
			// button is already made of; draw it in cream here so the armed action stays readable
			Panels.sprite(g, Kit.button(true, hover ? "hover" : "normal"), x, y, w, 20);
			String l = TextUtil.ellipsize(font, label, w - 12);
			Panels.text(g, font, l, x + (w - font.width(l)) / 2, y + 6, UiStyle.color("palette.ui.panel_hi", 0xFFFFFBF4));
			return;
		}
		Panels.button(g, font, label, x, y, w, false, hover, !enabled);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		// the row sits under its own buttons, so the buttons are tested first
		List<Btn> all = List.copyOf(buttons);
		Btn hit = null;
		for (int i = all.size() - 1; i >= 0 && hit == null; i--) {
			if (all.get(i).hit(event.x(), event.y())) {
				hit = all.get(i);
			}
		}
		// Every click is recorded, hit or miss. When someone says clicking this does nothing, the
		// first question is whether the screen was handed the click at all, and the second is where
		// it landed; without this there is no way to tell those two apart from outside the game.
		clicks++;
		lastClick = Math.round(event.x()) + "," + Math.round(event.y()) + " -> " + (hit == null ? "(nothing)" : hit.id())
			+ (hit != null && !hit.enabled() ? " (off)" : "") + (doubleClick ? " (double)" : "");
		if (hit == null) {
			return super.mouseClicked(event, doubleClick);
		}
		if (hit.enabled()) {
			press(hit, doubleClick);
		} else {
			feedback = whyOff(hit);
			feedbackError = true;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (maxScroll <= 0) {
			return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
		}
		scroll = Math.max(0, Math.min(maxScroll, scroll - (float) vertical * 24f));
		return true;
	}

	/** Press a button by id (also used by {@code dev.goals}): open:&lt;goal&gt;, stop, remove, clear. */
	public void press(String id) {
		for (Btn b : List.copyOf(buttons)) {
			if (b.id().equals(id)) {
				press(b, false);
				return;
			}
		}
	}

	private void press(Btn b, boolean repeat) {
		if (!b.enabled()) {
			feedback = whyOff(b);
			feedbackError = true;
			return;
		}
		if (b.id().startsWith("open:")) {
			String id = b.id().substring(5);
			// Clicking the open row folds it away again — but not the second half of a double
			// click, which would open it and close it in one gesture and look like nothing happened.
			selected = id.equals(selected) && !repeat ? null : id;
			confirm = null;
			feedback = null;
			return;
		}
		if (sending) {
			return;
		}
		switch (b.id()) {
			case "clear" -> arm("clear", null);
			case "stop" -> arm("cancel", selected);
			case "remove" -> arm("remove", selected);
			default -> {
			}
		}
	}

	/** First press arms the action and says what it will do; the second, within five seconds, sends it. */
	private void arm(String action, @Nullable String goalId) {
		if (!action.equals(confirm) || System.nanoTime() > confirmUntil) {
			confirm = action;
			confirmUntil = System.nanoTime() + CONFIRM_NANOS;
			feedback = null;
			feedbackError = false;
			return;
		}
		confirm = null;
		send(action, goalId);
	}

	private void send(String action, @Nullable String goalId) {
		long generation = ++requestGeneration;
		sending = true;
		feedback = action.equals("cancel") ? "Stopping…" : action.equals("remove") ? "Removing…" : "Clearing…";
		feedbackError = false;
		Foreman.goalAction(action, goalId).whenComplete((ack, err) -> {
			if (generation != requestGeneration) {
				return; // an older request must not overwrite a newer one's answer
			}
			sending = false;
			if (err != null) {
				feedback = "Not sent: " + (err.getMessage() == null ? err.toString() : err.getMessage());
				feedbackError = true;
				return;
			}
			if (!ack.ok()) {
				feedback = "Foreman: " + ack.error();
				feedbackError = true;
				return;
			}
			// the Foreman answers with the lines it also fed; they already name what went
			feedback = lines(ack.result());
			feedbackError = false;
			if (action.equals("remove") || action.equals("clear")) {
				selected = null;
			}
		});
	}

	private static String lines(@Nullable JsonObject result) {
		if (result == null || !result.has("lines")) {
			return "Done";
		}
		JsonArray ls = result.getAsJsonArray("lines");
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < ls.size(); i++) {
			b.append(i > 0 ? " · " : "").append(ls.get(i).getAsString());
		}
		return b.length() == 0 ? "Done" : b.toString();
	}

	/** Why a button is not taking clicks, in the words of whatever turned it off. */
	private String whyOff(Btn b) {
		if (sending) {
			return "Waiting for the Foreman to acknowledge the last action";
		}
		if (!Foreman.connected()) {
			return "Foreman offline: " + b.label() + " can't be sent yet";
		}
		if (b.id().equals("clear")) {
			return "Nothing to clear: every goal is still going";
		}
		Row sel = null;
		for (Row r : rows()) {
			if (r.goal().id().equals(selected)) {
				sel = r;
			}
		}
		if (sel == null) {
			return "Click a goal first";
		}
		if (b.id().equals("remove")) {
			return sel.live() > 0
				? sel.goal().id() + " still has " + sel.live() + " task(s) going. Press Stop goal first — that stops them and then this can remove them."
				: sel.goal().id() + " is still " + sel.goal().status().wire() + "; stop it first";
		}
		return sel.goal().id() + " is already " + sel.goal().status().wire() + " with nothing running";
	}

	private static String repoName(@Nullable String repoId) {
		ForemanState s = Foreman.state();
		if (repoId == null || s == null) {
			return repoId == null ? "" : "";
		}
		Repo r = s.repo(repoId);
		return r == null ? repoId : r.name();
	}

	static String statusLabel(Goal g) {
		return switch (g.status()) {
			case PLANNING -> "Planning";
			case ACTIVE -> "Going";
			case DONE -> "Done";
			case FAILED -> "Failed";
			case CANCELLED -> "Stopped";
			default -> g.status().wire();
		};
	}

	static String family(Goal g) {
		return switch (g.status()) {
			case PLANNING -> "thinking";
			case ACTIVE -> "working";
			case DONE -> "done";
			case FAILED -> "error";
			default -> "idle";
		};
	}

	private static String tint(Goal g) {
		return switch (g.status()) {
			case DONE -> "sage";
			case FAILED -> "red";
			case ACTIVE -> "clay";
			default -> "brass";
		};
	}

	/** The buttons as the last frame laid them out, for {@code dev.goals}: id, rect, enabled. */
	public JsonArray buttonRects() {
		JsonArray out = new JsonArray();
		for (Btn b : List.copyOf(buttons)) {
			JsonObject o = new JsonObject();
			o.addProperty("id", b.id());
			o.addProperty("rect", b.x() + " " + b.y() + " " + b.w() + "x" + b.h());
			o.addProperty("enabled", b.enabled());
			out.add(o);
		}
		return out;
	}

	/** The queue as the screen reads it, for {@code dev.goals}. */
	public JsonArray rowsJson() {
		JsonArray out = new JsonArray();
		for (Row r : rows()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", r.goal().id());
			o.addProperty("status", r.goal().status().wire());
			o.addProperty("repo", repoName(r.goal().repoId()));
			o.addProperty("done", r.done());
			o.addProperty("live", r.live());
			o.addProperty("stopped", r.stopped());
			o.addProperty("selected", r.goal().id().equals(selected));
			o.addProperty("text", r.goal().text());
			out.add(o);
		}
		return out;
	}

	public @Nullable String selected() {
		return selected;
	}

	/** The last click this screen was handed, {@code "x,y -> button"}, or null if it has had none. */
	public @Nullable String lastClick() {
		return lastClick;
	}

	/** How many clicks this screen has been handed, hits and misses alike. */
	public int clicks() {
		return clicks;
	}

	private static List<FormattedCharSequence> clip(List<FormattedCharSequence> lines, int max) {
		return lines.size() <= max ? lines : new ArrayList<>(lines.subList(0, max));
	}
}
