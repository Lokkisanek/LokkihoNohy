package com.bazaarflipper.mixin;

import com.bazaarflipper.BazaarFlipperMod;
import com.bazaarflipper.flip.FlipEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignEditScreen.class)
public abstract class SignEditScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        FlipEngine engine = BazaarFlipperMod.getEngine();
        if (engine == null) return;

        String value = engine.getPendingSignValue();
        if (value == null || value.isEmpty()) return;

        SignEditScreen self = (SignEditScreen) (Object) this;
        MinecraftClient mc = MinecraftClient.getInstance();

        // Schedule on next tick to let the screen finish initializing
        mc.execute(() -> {
            try {
                // Access the sign block entity via reflection
                var field = SignEditScreen.class.getDeclaredField("blockEntity");
                field.setAccessible(true);
                var blockEntity = (net.minecraft.block.entity.SignBlockEntity) field.get(self);

                if (blockEntity == null) return;

                // Send the sign update packet
                ClientPlayNetworkHandler handler = mc.getNetworkHandler();
                if (handler != null) {
                    handler.sendPacket(new UpdateSignC2SPacket(
                            blockEntity.getPos(),
                            true,
                            value, "", "", ""
                    ));
                }

                // Close the sign screen
                mc.setScreen(null);

                // Notify engine that sign input is complete
                engine.onSignCompleted();

            } catch (Exception e) {
                BazaarFlipperMod.LOGGER.error("[BazaarFlipper] Sign input failed: {}", e.getMessage());
            }
        });
    }
}
