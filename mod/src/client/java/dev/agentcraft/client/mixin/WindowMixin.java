package dev.agentcraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.agentcraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the window takes or loses focus. SDL delivers this before the button that came with it, so
 * it is the moment {@link MouseInput} measures an activating click against.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "onFocus", at = @At("HEAD"))
	private void agentcraft$onFocus(boolean focused, CallbackInfo ci) {
		MouseInput.focus(focused);
	}
}
