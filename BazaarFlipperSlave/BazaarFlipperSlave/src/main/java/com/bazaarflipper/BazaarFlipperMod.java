package com.bazaarflipper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BazaarFlipperSlave — Client-side Fabric mod.
 *
 * Architecture summary:
 *   - {@link TaskManager}  polls GET /api/task every ~1-2 s and POSTs status.
 *   - {@link ScreenTracker} tracks which Bazaar GUI is currently open.
 *   - {@link TaskExecutor}  is a tick-driven state machine that clicks through
 *     the Bazaar menus to fulfil the active task.
 *
 * All heavy I/O happens on a daemon ScheduledExecutorService thread.
 * All Minecraft interaction happens exclusively on the client game thread
 * inside ClientTickEvents.END_CLIENT_TICK.
 */
public class BazaarFlipperMod implements ClientModInitializer {

    public static final String MOD_ID = "bazaarflipper";
    public static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");

    // Singletons — accessible to mixins and helper classes
    private static TaskManager  taskManager;
    private static ScreenTracker screenTracker;
    private static TaskExecutor  taskExecutor;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[BazaarFlipper] Initialising BazaarFlipperSlave mod...");

        screenTracker = new ScreenTracker();
        taskManager   = new TaskManager();
        taskExecutor  = new TaskExecutor(screenTracker);

        // Register screen-open / screen-close hooks
        screenTracker.register();

        // Start polling the Python master server
        taskManager.startPolling();

        // Main game-thread loop
        ClientTickEvents.END_CLIENT_TICK.register(BazaarFlipperMod::onClientTick);

        LOGGER.info("[BazaarFlipper] Ready. Polling http://localhost:8000/api/task");
    }

    // -----------------------------------------------------------------------
    //  Tick handler — runs on the client thread every tick (~20×/s)
    // -----------------------------------------------------------------------

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Keep screen tracker aware of null screens (screen closed)
        screenTracker.onTick(client.currentScreen);

        // Drive the task executor
        com.bazaarflipper.model.Task task = taskManager.getCurrentTask();
        if (task != null && !task.isIdle()) {
            taskExecutor.tick(client, task);
        }
    }

    // -----------------------------------------------------------------------
    //  Static accessors (used by mixin & other classes)
    // -----------------------------------------------------------------------

    public static TaskManager   getTaskManager()   { return taskManager;   }
    public static ScreenTracker getScreenTracker() { return screenTracker; }
    public static TaskExecutor  getTaskExecutor()  { return taskExecutor;  }
}
