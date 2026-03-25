package net.lokkisan.bazzarclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class ExampleMod implements ClientModInitializer {
    private boolean enabled = false;
    private int cooldownTicks = 0;
    private int lastSlot = -1;
    private final Random random = new Random();
    private boolean isAwaitingResponse = false; 

    private String statusText = "STANDBY";
    private long lastChatReaction = 0;
    
    private static KeyBinding toggleBinding;

    // Pomocná funkce pro odesílání zpráv přímo do Minecraft chatu hráči (vidí to jen on)
    private void debugChat(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.of("§8[§6Overlord Debug§8] §7" + message), false);
        }
    }

    @Override
    public void onInitializeClient() {
        // Zkratka "R" pro zapnutí bota
        toggleBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.bazaarclient.toggle",
            GLFW.GLFW_KEY_R,
            "key.categories.misc" 
        ));

        // Zobrazení na obrazovce (HUD)
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            drawContext.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, 
                Text.of("§6[OVERLORD V10] §fStatus: " + statusText + " | Enabled: " + (enabled ? "§aYES" : "§cNO")), 
                10, 10, 0xFFFFFF);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 1. Zkontrolujeme, jestli hráč zmáčkl klávesu "R"
            while (toggleBinding.wasPressed()) {
                enabled = !enabled;
                statusText = enabled ? "§aACTIVE" : "§cSTANDBY";
                debugChat(client, "Bot byl " + (enabled ? "§aZAPNUT" : "§cVYPNUT"));
            }

            if (client.player == null || !enabled) return;

            // 2. Chat Simulator
            if (System.currentTimeMillis() < lastChatReaction) {
                statusText = "§eREADING CHAT...";
                return;
            }

            // 3. Jsme v menu?
            if (client.currentScreen == null) {
                isAwaitingResponse = false; 
                return;
            }

            if (cooldownTicks > 0) { cooldownTicks--; return; }

            // 4. Je otevřená truhla (GUI)?
            if (client.currentScreen instanceof GenericContainerScreen screen) {
                handleAdvancedLogic(client, screen);
            }
        });
    }

    private void handleAdvancedLogic(MinecraftClient client, GenericContainerScreen screen) {
        if (isAwaitingResponse) return; 

        // Získáme název okna (Odstraníme Hypixel barevné kódy, protože ty by to mohly blokovat)
        String rawTitle = screen.getTitle().getString().replaceAll("§[0-9a-fk-or]", "").trim();
        
        // Anti-Spam: Aby nám debug zprávy nespamovaly chat každou milisekundu,
        // vypíšeme je jen občas, nebo když se změní stav.
        if (client.world.getTime() % 40 == 0) { // Každé 2 vteřiny
            debugChat(client, "Otevřeno GUI: '" + rawTitle + "'");
        }

        // Zkontrolujeme Slot 13 (prostřední řada, 2. slot)
        ItemStack slotItem = screen.getScreenHandler().getSlot(13).getStack();
        String itemName = slotItem.getName().getString().replaceAll("§[0-9a-fk-or]", "").trim();

        if (client.world.getTime() % 40 == 0) {
            debugChat(client, "Vidím Slot 13: '" + itemName + "'");
        }

        if (slotItem.isEmpty() || itemName.equalsIgnoreCase("Air")) {
            statusText = "§7LAG BUFFER...";
            return;
        }

        // Kontrola, jestli je to opravdu Bazaar (Ignorujeme velikost písmen)
        if (rawTitle.toLowerCase().contains("bazaar")) {
            statusText = "§aDECIDING...";
            isAwaitingResponse = true;

            // Převedeme jméno na formát pro Python (např. "Iron Ingot" -> "IRON_INGOT")
            String idForPython = itemName.toUpperCase().replace(" ", "_");
            debugChat(client, "§bOdesílám do Pythonu: " + idForPython);

            // Asynchronní dotaz na Python (Zabraňuje seknutí hry)
            CompletableFuture.supplyAsync(() -> BazaarClient.getDecisionFromAI(idForPython))
                .thenAccept(action -> {
                    client.execute(() -> { 
                        isAwaitingResponse = false;
                        if (action == 1 && client.currentScreen == screen) { 
                            debugChat(client, "§aPython odpověděl: KLIKNI (KUP/PRODEJ)!");
                            executeHumanClick(client, screen, 13);
                        } else {
                            if (client.world.getTime() % 40 == 0) {
                                debugChat(client, "§ePython odpověděl: ČEKEJ.");
                            }
                            statusText = "§7WAITING...";
                        }
                    });
                });

        } else if (rawTitle.toLowerCase().contains("confirm")) {
            debugChat(client, "§6Jsem v Potvrzovacím (Confirm) menu - provádím auto-klik!");
            executeHumanClick(client, screen, 13);
        } else {
            if (client.world.getTime() % 40 == 0) {
                debugChat(client, "§cIgnoruji toto menu (Není to Bazaar ani Confirm).");
            }
        }
    }

    private void executeHumanClick(MinecraftClient client, GenericContainerScreen screen, int slot) {
        long delay = StealthMouse.calculateHumanDelay(lastSlot, slot);
        cooldownTicks = (int) (delay / 50) + 1; 

        // Šance na miss-click (simulace člověka - 1%)
        if (random.nextFloat() < 0.01) {
            debugChat(client, "§c(Oops, miss-click simulace)");
            client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot + 1, 0, SlotActionType.PICKUP, client.player);
            cooldownTicks += 10; 
        }

        // Správný klik
        client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot, 0, SlotActionType.PICKUP, client.player);
        lastSlot = slot;
        statusText = "§bMOVING MOUSE...";
    }
}