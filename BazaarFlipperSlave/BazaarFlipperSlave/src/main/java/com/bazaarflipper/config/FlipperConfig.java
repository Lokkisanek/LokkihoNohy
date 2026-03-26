package com.bazaarflipper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FlipperConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bazaarflipper.json";

    // API
    public String apiKey = "";

    // Mode
    public FlipMode mode = FlipMode.BALANCED;

    // Budget
    public double budget = 10_000_000;
    public double maxPerFlipPct = 20.0;
    public double minCashReserve = 100_000;

    // Flipping
    public double minMarginPct = 2.0;
    public int flipAmountPerOrder = 64;
    public String specificItemId = "";

    // Safety
    public int sessionLimitMinutes = 120;
    public int breakDurationMinutes = 5;
    public int nearbyPlayerThreshold = 3;
    public double nearbyPlayerRadius = 6.0;

    // Debug
    public boolean debug = false;

    // Delays (ms) — human-like
    public int clickDelayMean = 350;
    public int clickDelayStdDev = 120;
    public int navigationDelayMean = 1200;
    public int navigationDelayStdDev = 400;
    public int orderCheckIntervalSec = 45;

    // Computed
    public double maxPerFlipCoins() {
        return budget * (maxPerFlipPct / 100.0);
    }

    public int maxFlipAmount(double pricePerUnit) {
        if (pricePerUnit <= 0) return flipAmountPerOrder;
        return Math.min(flipAmountPerOrder, (int) (maxPerFlipCoins() / pricePerUnit));
    }

    // Persistence

    public static FlipperConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                FlipperConfig cfg = GSON.fromJson(json, FlipperConfig.class);
                if (cfg != null) {
                    LOGGER.info("[BazaarFlipper] Config loaded from {}", path);
                    return cfg;
                }
            } catch (Exception e) {
                LOGGER.warn("[BazaarFlipper] Failed to load config: {}", e.getMessage());
            }
        }
        FlipperConfig cfg = new FlipperConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.warn("[BazaarFlipper] Failed to save config: {}", e.getMessage());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
