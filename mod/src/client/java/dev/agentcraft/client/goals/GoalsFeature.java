package dev.agentcraft.client.goals;

import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.hq.StatusLampRenderer;
import dev.agentcraft.client.world.StationInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The goal queue. The Goal Atrium's plinth is the lamp bound to {@code goal:atrium} that projects
 * the hologram; right-clicking it opens {@link GoalScreen} on the goals behind that hologram —
 * every goal the team has, not just the current one — where they can be stopped and removed.
 * {@code /goals} opens the same screen.
 *
 * <p>Dev: {@code dev.goals} returns the queue as the screen reads it; {@code {open: goalId}}
 * opens it (on that goal), {@code {press: button}} presses one (open:&lt;goal&gt; stop remove clear).
 */
public final class GoalsFeature {
	private GoalsFeature() {
	}

	public static void init() {
		// The hologram floats three and a half blocks up and is not a block, so there is nothing
		// there to click: the plinth under it is the thing you walk up to, and it is a status lamp
		// like every other. Only the atrium's own lamp opens the queue; the agent and CI lamps
		// elsewhere in the building keep doing nothing.
		StationInteractions.onUse(ModBlocks.STATUS_LAMP, (player, pos, state, be) -> {
			if (be != null && StatusLampRenderer.ATRIUM_BINDING.equals(be.binding())) {
				open(null);
			}
		});
		DevBridge.registerScreen("goals", mc -> new GoalScreen(null));
		registerDev();
	}

	/** Open the goal queue; {@code goalId} null = nothing selected, else that goal with its actions. */
	public static void open(@Nullable String goalId) {
		Minecraft.getInstance().gui.setScreen(new GoalScreen(goalId));
	}

	private static void registerDev() {
		DevBridge.register("dev.goals", 10_000, "{open?: goalId|\"\", press?: button} -> the goal queue as the screen reads it; opens it / presses a button",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String goalId = f.optStr("open", null);
				boolean wantsOpen = f.has("open");
				String press = f.optStr("press", null);
				return DevBridge.onClient(mc, () -> {
					Screen cur = mc.gui.screen();
					GoalScreen screen;
					if (wantsOpen || !(cur instanceof GoalScreen)) {
						screen = new GoalScreen(goalId == null || goalId.isEmpty() ? null : goalId);
						mc.gui.setScreen(screen);
					} else {
						screen = (GoalScreen) cur;
					}
					if (press != null) {
						screen.press(press);
					}
					JsonObject o = new JsonObject();
					o.add("goals", screen.rowsJson());
					o.addProperty("selected", screen.selected() == null ? "" : screen.selected());
					o.add("buttons", screen.buttonRects());
					return o;
				});
			});
	}
}
