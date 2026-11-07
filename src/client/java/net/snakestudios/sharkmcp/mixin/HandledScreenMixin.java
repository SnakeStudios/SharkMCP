package net.snakestudios.sharkmcp.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's HandledScreen.tick() calls MinecraftClient.player.isAlive() without a null
 * check. A proxy transfer or a disconnect can leave the player null while an inventory
 * or chest screen is still open, which crashes the game on the next tick. Skip the tick
 * in that state; the mod closes the stale screen on the next client tick.
 */
@Mixin(HandledScreen.class)
abstract class HandledScreenMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void sharkmcp$skipTickWithoutPlayer(CallbackInfo ci) {
		if (MinecraftClient.getInstance().player == null) ci.cancel();
	}
}
