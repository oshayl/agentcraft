package dev.agentcraft.client.mixin;

import dev.agentcraft.client.input.MouseInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every mouse button Minecraft routes, counted (see {@link MouseInput}), and the click that only
 * brought the window to the front thrown away when there is no screen to take it.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void agentcraft$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (MouseInput.onButton(info.button(), action, Minecraft.getInstance().gui.screen())) {
			ci.cancel();
		}
	}
}
