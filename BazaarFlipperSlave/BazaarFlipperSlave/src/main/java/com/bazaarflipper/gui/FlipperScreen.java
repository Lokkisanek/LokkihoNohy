package com.bazaarflipper.gui;

import com.bazaarflipper.BazaarFlipperMod;
import com.bazaarflipper.config.FlipMode;
import com.bazaarflipper.config.FlipperConfig;
import com.bazaarflipper.flip.FlipEngine;
import com.bazaarflipper.flip.FlipTask;
import com.bazaarflipper.market.BazaarApi;
import com.bazaarflipper.market.BazaarProduct;
import com.bazaarflipper.market.MarketAnalyzer;
import com.bazaarflipper.util.FlipLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

public class FlipperScreen extends Screen {
    private final FlipperConfig config;
    private final FlipEngine engine;
    private final BazaarApi api;
    private final FlipLog log;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget budgetField;
    private TextFieldWidget maxPctField;
    private TextFieldWidget specificItemField;
    private ButtonWidget modeButton;
    private ButtonWidget startStopButton;

    private int scrollOffset = 0;

    public FlipperScreen(FlipperConfig config, FlipEngine engine, BazaarApi api, FlipLog log) {
        super(Text.literal("BazaarFlipper"));
        this.config = config;
        this.engine = engine;
        this.api = api;
        this.log = log;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 25;

        // API Key
        apiKeyField = new TextFieldWidget(textRenderer, cx - 120, y, 240, 16, Text.literal("API Key"));
        apiKeyField.setMaxLength(64);
        apiKeyField.setText(config.apiKey);
        apiKeyField.setChangedListener(text -> {
            config.apiKey = text;
            api.setApiKey(text);
        });
        addDrawableChild(apiKeyField);
        y += 24;

        // Mode selector
        modeButton = ButtonWidget.builder(
                Text.literal("Mode: " + config.mode.displayName),
                button -> {
                    config.mode = config.mode.next();
                    button.setMessage(Text.literal("Mode: " + config.mode.displayName));
                }
        ).dimensions(cx - 120, y, 115, 20).build();
        addDrawableChild(modeButton);

        // Specific item field (only relevant for SPECIFIC mode)
        specificItemField = new TextFieldWidget(textRenderer, cx + 5, y, 115, 16,
                Text.literal("Item ID"));
        specificItemField.setMaxLength(64);
        specificItemField.setText(config.specificItemId);
        specificItemField.setChangedListener(text -> config.specificItemId = text.toUpperCase());
        addDrawableChild(specificItemField);
        y += 24;

        // Budget
        budgetField = new TextFieldWidget(textRenderer, cx - 120, y, 100, 16,
                Text.literal("Budget"));
        budgetField.setMaxLength(12);
        budgetField.setText(String.valueOf((long) config.budget));
        budgetField.setChangedListener(text -> {
            try { config.budget = Double.parseDouble(text); } catch (NumberFormatException ignored) {}
        });
        addDrawableChild(budgetField);

        // Max % per flip
        maxPctField = new TextFieldWidget(textRenderer, cx + 20, y, 50, 16,
                Text.literal("%"));
        maxPctField.setMaxLength(5);
        maxPctField.setText(String.valueOf((int) config.maxPerFlipPct));
        maxPctField.setChangedListener(text -> {
            try { config.maxPerFlipPct = Double.parseDouble(text); } catch (NumberFormatException ignored) {}
        });
        addDrawableChild(maxPctField);
        y += 24;

        // Start/Stop button
        startStopButton = ButtonWidget.builder(
                Text.literal(engine.isRunning() ? "§c■ STOP" : "§a▶ START"),
                button -> {
                    if (engine.isRunning()) {
                        engine.stop(MinecraftClient.getInstance());
                        button.setMessage(Text.literal("§a▶ START"));
                    } else {
                        config.save();
                        engine.start();
                        button.setMessage(Text.literal("§c■ STOP"));
                    }
                }
        ).dimensions(cx - 60, y, 120, 20).build();
        addDrawableChild(startStopButton);
        y += 26;

        // Save button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Save Config"),
                button -> {
                    config.save();
                }
        ).dimensions(cx + 70, y - 26, 80, 20).build());

        // Debug toggle
        addDrawableChild(ButtonWidget.builder(
                Text.literal(config.debug ? "§aDebug: ON" : "§7Debug: OFF"),
                button -> {
                    config.debug = !config.debug;
                    button.setMessage(Text.literal(config.debug ? "§aDebug: ON" : "§7Debug: OFF"));
                }
        ).dimensions(cx - 170, y - 26, 70, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int y = 25;

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§6§lBazaarFlipper"),
                cx, 10, 0xFFFFFF);

        // Labels
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7API Key:"),
                cx - 170, y + 4, 0xAAAAAA);
        y += 24;

        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Item (Specific):"),
                cx + 5, y - 7, 0x888888);
        y += 24;

        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Budget:"),
                cx - 170, y + 4, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Max %:"),
                cx - 8, y + 4, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7%"),
                cx + 73, y + 4, 0xAAAAAA);
        y += 24;

        // Status line
        y += 26;
        FlipTask task = engine.getCurrentTask();
        String status = engine.isRunning()
                ? (task != null ? "§a● " + task.phase.name() + " — " + task.itemId
                : "§a● Running (idle)")
                : "§c○ Stopped";
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Status: " + status),
                cx - 170, y, 0xFFFFFF);
        y += 12;

        // Market data status
        String dataStatus = api.hasData()
                ? "§a" + api.getAllProducts().size() + " products loaded"
                : "§cNo market data";
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Market: " + dataStatus),
                cx - 170, y, 0xFFFFFF);
        y += 12;

        // Safety info
        ctx.drawTextWithShadow(textRenderer, Text.literal(
                        "§7Nearby players: §f" + BazaarFlipperMod.getSafety().getNearbyPlayerCount()),
                cx - 170, y, 0xFFFFFF);
        y += 16;

        // Top items
        ctx.drawTextWithShadow(textRenderer, Text.literal("§e§lTop Flip Items:"),
                cx - 170, y, 0xFFFFFF);
        y += 12;

        if (api.hasData()) {
            List<BazaarProduct> top = MarketAnalyzer.findBestFlips(api.getAllProducts(), config, 5);
            for (int i = 0; i < top.size() && i < 5; i++) {
                BazaarProduct p = top.get(i);
                String line = String.format("§f%d. §b%s §7roi=§a%.1f%% §7vol=§e%.0f/d §7score=§6%.4f",
                        i + 1, p.displayName(), p.marginPct(), p.effectiveDailyVolume(),
                        p.flipScore(1.0, 0.5, 0.3, 0.15));
                ctx.drawTextWithShadow(textRenderer, Text.literal(line),
                        cx - 170, y, 0xFFFFFF);
                y += 10;
            }
        }
        y += 6;

        // Session stats
        ctx.drawTextWithShadow(textRenderer, Text.literal(
                        String.format("§7Session: §a%d flips §7profit=§6%.0f coins",
                                log.getFlipsCompleted(), log.getSessionProfit())),
                cx - 170, y, 0xFFFFFF);
        y += 14;

        // Live log (scrollable)
        ctx.drawTextWithShadow(textRenderer, Text.literal("§e§lLive Log:"),
                cx - 170, y, 0xFFFFFF);
        y += 12;

        List<String> entries = log.getEntries();
        int maxLines = Math.min(8, (this.height - y - 10) / 10);
        int start = Math.max(0, entries.size() - maxLines - scrollOffset);
        int end = Math.min(entries.size(), start + maxLines);

        for (int i = start; i < end; i++) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + entries.get(i)),
                    cx - 170, y, 0xCCCCCC);
            y += 10;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int logSize = log.getEntries().size();
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) verticalAmount, logSize - 5));
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        config.save();
        super.close();
    }
}
