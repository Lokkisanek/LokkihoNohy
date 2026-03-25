package net.lokkisan.bazzarclient;

import net.fabricmc.api.ClientModInitializer;

public class BazaarClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Tento řádek aktivuje náš mapper
        GuiMapper.register();
    }
}