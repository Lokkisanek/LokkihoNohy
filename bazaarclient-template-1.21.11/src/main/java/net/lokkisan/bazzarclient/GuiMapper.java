package net.lokkisan.bazzarclient;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.lokkisan.bazzarclient.mixin.HandledScreenAccessor;
import java.io.FileWriter;
import java.io.IOException;
import java.io.File;

public class GuiMapper {

   public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof HandledScreen<?> handledScreen) {
                // Registrace naslouchátka používá pouze souřadnice a tlačítko (handedScreen je vázaný)
                ScreenMouseEvents.afterMouseClick(handledScreen).register((mouseX, mouseY, button) -> {

                    Slot hoveredSlot = ((HandledScreenAccessor) handledScreen).getFocusedSlot();
                    
                    if (hoveredSlot != null) {
                        String guiName = screen.getTitle().getString();
                        int slotId = hoveredSlot.id;
                        String itemName = hoveredSlot.hasStack() ? hoveredSlot.getStack().getName().getString() : "Empty Slot";

                        saveToFile(guiName, slotId, itemName);
                    }
                    return false;
                });
            }
        });
    }
    private static void saveToFile(String gui, int id, String item) {
        try {
            // Ukládá se do souboru v hlavní složce hry
            File file = new File(MinecraftClient.getInstance().runDirectory, "bazaar_log.txt");
            FileWriter writer = new FileWriter(file, true);
            writer.write("GUI: " + gui + " | Slot ID: " + id + " | Item: " + item + "\n");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}