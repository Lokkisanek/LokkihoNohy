package net.lokkisan.bazzarclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import net.minecraft.util.Identifier;
import java.util.List;

public class ExampleMod implements ClientModInitializer {
    private static KeyBinding toggleBinding;
    private boolean botEnabled = false;
    private int actionCooldown = 0;
    private int lastClickedSlot = -1;

    @Override
    public void onInitializeClient() {
        // OPRAVENO: Používáme nejjednodušší konstruktor, který v 1.21.11 funguje nejlépe
        toggleBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.bazaarclient.toggle",
            InputUtil.Type.KEYSYM, // Tohle tam MUSÍ být
            GLFW.GLFW_KEY_R,
            KeyBinding.Category.MISC
        ));
        // Registrace příkazu /ai_setup
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("ai_setup")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.of("§6[AI Bot] Setup dokončen."));
                    return 1;
                }));
        });

        // Hlavní smyčka (Tick)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (toggleBinding.wasPressed()) {
                botEnabled = !botEnabled;
                String status = botEnabled ? "§aZAPNUT" : "§cVYPNUT";
                client.player.sendMessage(Text.of("§6[AI Bot] " + status), true);
            }

            if (!botEnabled || client.currentScreen == null) return;

            // Failsafe: Pokud hráč dostane poškození, bot se vypne
            if (client.player.hurtTime > 0) {
                botEnabled = false;
                client.player.sendMessage(Text.of("§c[FAILSAFE] Detekován hit! Bot zastaven."), false);
                return;
            }

            if (client.currentScreen instanceof GenericContainerScreen screen) {
                if (actionCooldown > 0) {
                    actionCooldown--;
                    return;
                }

                String title = screen.getTitle().getString();
                
                if (title.contains("Bazaar")) {
                    handleBazaarMain(client, screen);
                } else if (title.contains("Buy Order") || title.contains("Confirm")) {
                    // Klik na potvrzení v podmenu
                    executeStealthClick(client, screen, title.contains("Confirm") ? 13 : 31);
                }
            }
        });
    }

    private void handleBazaarMain(MinecraftClient client, GenericContainerScreen screen) {
        ItemStack item = screen.getScreenHandler().getSlot(13).getStack();
        if (item.isEmpty() || item.getName().getString().equals("Air")) return;

        // Přečtení Lore pro ceny
        List<Text> tooltip = item.getTooltip(
            net.minecraft.item.Item.TooltipContext.create(client.world), 
            client.player, 
            TooltipType.BASIC
        );
        
        double buyPrice = 0;
        double sellPrice = 0;
        for (Text line : tooltip) {
            String text = line.getString().toLowerCase().replace(",", "");
            if (text.contains("buy price")) buyPrice = extractPrice(text);
            if (text.contains("sell price")) sellPrice = extractPrice(text);
        }

        if (buyPrice > 0 && sellPrice > 0) {
            int action = BazaarClient.getDecisionFromAI(item.getName().getString(), sellPrice, buyPrice);
            if (action == 1) { 
                executeStealthClick(client, screen, 13);
            }
        }
    }

    private void executeStealthClick(MinecraftClient client, GenericContainerScreen screen, int slot) {
        // Výpočet prodlevy pro stealth pohyb
        long delayMs = MouseHelper.getWaitTime(lastClickedSlot, slot);
        actionCooldown = (int) (delayMs / 50) + 1;

        if (client.interactionManager != null) {
            client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId, slot, 0, SlotActionType.PICKUP, client.player
            );
        }
        lastClickedSlot = slot;
    }

    private double extractPrice(String text) {
        try {
            String clean = text.replaceAll("[^0-9.]", "");
            return clean.isEmpty() ? 0 : Double.parseDouble(clean);
        } catch (Exception e) {
            return 0;
        }
    }
}