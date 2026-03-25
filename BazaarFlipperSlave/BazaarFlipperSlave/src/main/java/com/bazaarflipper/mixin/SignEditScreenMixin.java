package com.bazaarflipper.mixin;

import com.bazaarflipper.BazaarFlipperMod;
import com.bazaarflipper.TaskExecutor;
import com.bazaarflipper.model.Task;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the Sign GUI that Hypixel uses for custom amount / price entry.
 *
 * When the {@link TaskExecutor} is in WAIT_SIGN_INPUT phase, this mixin
 * detects the sign opening, injects the desired value as the first line,
 * and calls {@link ClientPlayNetworkHandler} to finalize the packet —
 * exactly as if the player had typed it.
 *
 * The mixin is injected at the point where SignEditScreen calls finalize
 * (i.e., the player presses Done / Enter on the sign editor).
 */
@Mixin(SignEditScreen.class)
public abstract class SignEditScreenMixin {

    /**
     * Called when the sign editor is first opened (after_init equivalent).
     * We schedule the auto-input on the next tick so the screen is fully ready.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Task task = BazaarFlipperMod.getTaskManager().getCurrentTask();
        if (task == null || task.isIdle()) return;

        TaskExecutor executor = BazaarFlipperMod.getTaskExecutor();
        if (executor.isWaitingForSignInput()) {
            // Determine what value to inject based on task context
            // Hypixel sign: line 0 = value, lines 1-3 = empty
            String value = resolveSignValue(task);
            scheduleSignInput((SignEditScreen) (Object) this, value, executor);
        }
    }

    /**
     * Decides what text to type on the sign — either the amount or the price,
     * depending on the screen title context.
     */
    private String resolveSignValue(Task task) {
        String title = BazaarFlipperMod.getScreenTracker().getRawTitle();
        if (title.toLowerCase().contains("price") || title.toLowerCase().contains("custom")) {
            // Format price as an integer (Hypixel drops decimals on sign input)
            return String.valueOf((long) task.price);
        }
        return String.valueOf(task.amount);
    }

    /**
     * Schedules sign input on the next client tick to avoid race conditions
     * with the packet handler.
     */
    private void scheduleSignInput(SignEditScreen screen, String value, TaskExecutor executor) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        mc.execute(() -> {
            try {
                // Use reflection to access the private 'blockEntity' field
                // (Yarn-mapped: field_2392 / blockEntity depending on version)
                var field = SignEditScreen.class.getDeclaredField("blockEntity");
                field.setAccessible(true);
                var blockEntity = (net.minecraft.block.entity.SignBlockEntity) field.get(screen);

                // Set line 0 of the front text
                blockEntity.getFrontText().getMessages(false); // ensure initialized
                // Directly set via setText packet equivalent
                String[] lines = {"", "", "", ""};
                lines[0] = value;

                // Send the UpdateSign packet to the server
                ClientPlayNetworkHandler handler = mc.getNetworkHandler();
                if (handler != null && blockEntity != null) {
                    handler.sendPacket(new UpdateSignC2SPacket(
                            blockEntity.getPos(),
                            true,       // isFront
                            lines[0], lines[1], lines[2], lines[3]
                    ));
                }

                // Close the sign screen
                mc.setScreen(null);

                // Notify the executor that sign input is done
                executor.signInputComplete();

            } catch (Exception e) {
                BazaarFlipperMod.LOGGER.error("[BazaarFlipper] Sign input mixin failed: {}",
                        e.getMessage());
            }
        });
    }
}
