package dev.agentcraft.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sets SDL hints before SDL is initialised (26.3 uses SDL3 for windowing/input, not GLFW).
 * By default the window is shown WITHOUT activating it, so a dev launch never steals focus.
 * Set AGENTCRAFT_FOCUS=1 to get the normal "come to front" behaviour.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
	@Inject(method = "initBackendSystem", at = @At("HEAD"), remap = false)
	private static void agentcraft$sdlHints(CallbackInfoReturnable<?> cir) {
		// Deliver the click that brings the window to the front instead of swallowing it. On macOS
		// that click is the window manager's, not the app's, so tabbing in from a terminal and
		// clicking a button did nothing at all — every first click was eaten, which looks exactly
		// like a broken screen. MouseInput throws it away again when no screen is open, so this
		// cannot make a stray click swing at the world.
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH, "1");
		if (!ClientEnv.TAKE_FOCUS) {
			SDLHints.SDL_SetHint("SDL_WINDOW_ACTIVATE_WHEN_SHOWN", "0");
			SDLHints.SDL_SetHint("SDL_WINDOW_ACTIVATE_WHEN_RAISED", "0");
			SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "0");
			AgentCraft.LOGGER.info("Window will open without taking focus (AGENTCRAFT_FOCUS=1 to change)");
		}
	}
}
