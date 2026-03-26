package com.bazaarflipper;

import com.bazaarflipper.config.FlipperConfig;
import com.bazaarflipper.flip.FlipEngine;
import com.bazaarflipper.gui.FlipperScreen;
import com.bazaarflipper.market.BazaarApi;
import com.bazaarflipper.safety.SafetyManager;
import com.bazaarflipper.util.FlipLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BazaarFlipperMod implements ClientModInitializer {

    public static final String MOD_ID = "bazaarflipper";
    public static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");

    private static FlipperConfig config;
    private static BazaarApi api;
    private static FlipLog log;
    private static SafetyManager safety;
    private static FlipEngine engine;

    private static KeyBinding openGuiKey;
    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[BazaarFlipper] Initializing...");

        // Load config
        config = FlipperConfig.load();

        // Init components
        log = new FlipLog();
        api = new BazaarApi();
        api.setApiKey(config.apiKey);
        safety = new SafetyManager(config, log);
        engine = new FlipEngine(config, api, safety, log);

        // Start API fetcher
        api.start();

        // Register keybinds
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazaarflipper.open_gui",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B,
                KeyBinding.Category.MISC
        ));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazaarflipper.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6,
                KeyBinding.Category.MISC
        ));

        // Client tick
        ClientTickEvents.END_CLIENT_TICK.register(BazaarFlipperMod::onClientTick);

        // Chat message listener for safety
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                safety.onChatMessage(message.getString());
            }
        });

        LOGGER.info("[BazaarFlipper] Ready. Press B to open GUI, F6 to toggle.");
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Handle keybinds
        while (openGuiKey.wasPressed()) {
            client.setScreen(new FlipperScreen(config, engine, api, log));
        }
        while (toggleKey.wasPressed()) {
            if (engine.isRunning()) {
                engine.stop(client);
                log.log("§cBot stopped via keybind.");
            } else {
                engine.start();
                log.log("§aBot started via keybind.");
            }
        }

        // Drive flip engine
        engine.tick(client);
    }

    // Static accessors
    public static FlipEngine getEngine() { return engine; }
    public static FlipperConfig getConfig() { return config; }
    public static BazaarApi getApi() { return api; }
    public static FlipLog getLog() { return log; }
    public static SafetyManager getSafety() { return safety; }
}
