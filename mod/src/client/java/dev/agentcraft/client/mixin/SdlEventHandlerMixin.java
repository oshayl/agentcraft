package dev.agentcraft.client.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;
import dev.agentcraft.client.input.MouseInput;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The earliest point a click exists in the game: the SDL event itself. Counted so that a click
 * that never arrives can be told apart from one the game threw away (see {@link MouseInput}).
 */
@Mixin(SDLEventHandler.class)
public abstract class SdlEventHandlerMixin {
	@Inject(method = "handleMouseButtonEvent", at = @At("HEAD"))
	private void agentcraft$mouseButton(SDL_Event event, CallbackInfo ci) {
		MouseInput.sdlButton(event.type(), event.button().button());
	}
}
