package dev.agentcraft.client.input;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * What happens to a mouse button between the window and the screen, and the one place that decides
 * a click is not meant for the game.
 *
 * <p>Two things live here, both of them answers to the same bug: the goal queue took no clicks at
 * all, and from outside the game there was no way to tell a screen that ignores clicks from a click
 * that never arrived.
 *
 * <p><b>The trace</b> counts buttons at each stage — the SDL event the window was handed, the
 * handler Minecraft routes it to, and (per screen) whether the screen saw it. {@code dev.state
 * mouse.events} reports it, so "clicking does nothing" can be placed at a stage instead of guessed
 * at.
 *
 * <p><b>The activating click</b>: macOS does not deliver the click that brings a background window
 * to the front, and SDL drops button events for an unfocused window unless told otherwise. In a
 * tool you tab into from a terminal all day that eats the first click every single time, which is
 * exactly what it looks like when a screen is broken. {@code RenderSystemMixin} turns the SDL hint
 * on so the click arrives; this class then throws it away again when there is no screen open, so
 * tabbing back into the world cannot swing at a block you were only pointing at.
 */
public final class MouseInput {
	/** How long after the window takes focus a click still counts as the one that focused it. */
	private static final long ACTIVATING_MS = 400;

	private static long focusGainedAt = -1;
	private static boolean focused;
	private static int sdlButtons;
	private static int handlerButtons;
	private static int dropped;
	private static @Nullable String lastSdl;
	private static @Nullable String lastHandler;

	private MouseInput() {
	}

	/** The window gained or lost focus (SDL's own event, before any button that came with it). */
	public static void focus(boolean gained) {
		focused = gained;
		if (gained) {
			focusGainedAt = System.currentTimeMillis();
		}
	}

	/** SDL handed the window a mouse button event: 1025 = down, 1026 = up. */
	public static void sdlButton(int type, int button) {
		sdlButtons++;
		lastSdl = (type == 1025 ? "down" : type == 1026 ? "up" : "type" + type) + " button" + button + " focused=" + focused;
	}

	/**
	 * Minecraft is about to route a button to the open screen or to the world.
	 *
	 * @return true to throw it away: the click that activated the window, with no screen to take it.
	 */
	public static boolean onButton(int button, int action, @Nullable Screen screen) {
		handlerButtons++;
		boolean press = action == 1;
		long sinceFocus = focusGainedAt < 0 ? Long.MAX_VALUE : System.currentTimeMillis() - focusGainedAt;
		boolean activating = press && screen == null && sinceFocus <= ACTIVATING_MS;
		lastHandler = (press ? "press" : "release") + " button" + button + " screen=" + (screen == null ? "none" : screen.getClass().getSimpleName())
			+ (activating ? " DROPPED (activating click)" : "");
		if (activating) {
			dropped++;
		}
		return activating;
	}

	/** The stages a button passed through, for {@code dev.state}. */
	public static JsonObject trace() {
		JsonObject o = new JsonObject();
		o.addProperty("sdl", sdlButtons);
		o.addProperty("handler", handlerButtons);
		o.addProperty("droppedActivating", dropped);
		o.addProperty("lastSdl", lastSdl);
		o.addProperty("lastHandler", lastHandler);
		o.addProperty("focusedAgoMs", focusGainedAt < 0 ? null : System.currentTimeMillis() - focusGainedAt);
		Screen screen = Minecraft.getInstance().gui.screen();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		return o;
	}
}
