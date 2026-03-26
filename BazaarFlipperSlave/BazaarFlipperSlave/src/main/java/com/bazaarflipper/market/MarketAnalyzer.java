package com.bazaarflipper.market;

import com.bazaarflipper.config.FlipperConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class MarketAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");

    private static final Set<String> BLACKLISTED = Set.of(
            // Cookies and special items
            "BAZAAR_COOKIE", "BOOSTER_COOKIE", "KISMET_FEATHER",
            "STOCK_OF_STONKS", "JERRY_BOX_GREEN", "JERRY_BOX_BLUE",
            "JERRY_BOX_PURPLE", "JERRY_BOX_GOLDEN",
            // Basic vanilla items — too cheap, too volatile, not profitable
            "FEATHER", "COBBLESTONE", "DIRT", "SAND", "GRAVEL", "FLINT",
            "COAL", "CHARCOAL", "BONE", "ROTTEN_FLESH", "GUNPOWDER",
            "STRING", "SPIDER_EYE", "SLIME_BALL", "ARROW",
            "SUGAR_CANE", "WHEAT", "POTATO_ITEM", "CARROT_ITEM",
            "INK_SACK", "SEEDS", "MELON", "PUMPKIN",
            "RAW_CHICKEN", "RAW_BEEF", "PORK", "MUTTON", "RABBIT",
            "LEATHER", "CLAY_BALL", "SNOW_BALL",
            "RED_MUSHROOM", "BROWN_MUSHROOM", "CACTUS",
            "NETHER_STALK", "SULPHUR", "MAGMA_CREAM", "GHAST_TEAR",
            "LOG", "LOG:1", "LOG:2", "LOG:3", "LOG_2", "LOG_2:1",
            "IRON_INGOT", "GOLD_INGOT", "DIAMOND",
            "ICE", "PACKED_ICE", "NETHERRACK", "OBSIDIAN",
            "ENDER_PEARL", "BLAZE_ROD", "GLOWSTONE_DUST",
            "RAW_FISH", "RAW_FISH:1", "RAW_FISH:2", "RAW_FISH:3"
    );

    // Minimum unit price — items under this are not worth the bot's time
    private static final double MIN_UNIT_PRICE = 10.0;
    // Minimum absolute profit per unit (coins) — avoids flipping pennies on bulk items
    private static final double MIN_PROFIT_PER_UNIT = 1.0;

    // Default weights — balanced between profit, liquidity, stability, competition
    // w1 = ROI weight, w2 = liquidity weight, w3 = stability weight, k = competition penalty
    private static final double W1 = 1.0;
    private static final double W2 = 0.5;
    private static final double W3 = 0.3;
    private static final double K = 0.15;

    /**
     * Find best flips using the scoring formula:
     * S = ROI^w1 * log10(Veff)^w2 * (1/(1+cv))^w3 * e^(-k*C)
     */
    public static List<BazaarProduct> findBestFlips(Map<String, BazaarProduct> products,
                                                     FlipperConfig config, int count) {
        List<BazaarProduct> candidates = products.values().stream()
                .filter(p -> !BLACKLISTED.contains(p.productId))
                // Must have positive margin after tax
                .filter(p -> p.roi() > 0)
                // Must have valid prices above minimum
                .filter(p -> p.topBuyOrderPrice >= MIN_UNIT_PRICE && p.topSellOrderPrice >= MIN_UNIT_PRICE)
                // Must have minimum absolute profit per unit
                .filter(p -> p.marginPerUnit() >= MIN_PROFIT_PER_UNIT)
                // Must be affordable (at least 1 unit)
                .filter(p -> p.ourBuyPrice() <= config.maxPerFlipCoins())
                // Must have some volume (at least 100/day effective)
                .filter(p -> p.effectiveDailyVolume() >= 100)
                // Score and sort
                .sorted(Comparator.comparingDouble((BazaarProduct p) ->
                        p.flipScore(W1, W2, W3, K)).reversed())
                .limit(count)
                .collect(Collectors.toList());

        // Debug log the top items
        if (!candidates.isEmpty()) {
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                BazaarProduct p = candidates.get(i);
                LOGGER.info("[BazaarFlipper] Top#{}: {} ROI={}% vol={}/d comp={} score={}",
                        i + 1, p.productId,
                        String.format("%.2f", p.marginPct()),
                        String.format("%.0f", p.effectiveDailyVolume()),
                        String.format("%.1f", p.competition()),
                        String.format("%.6f", p.flipScore(W1, W2, W3, K)));
            }
        }

        return candidates;
    }

    public static List<BazaarProduct> getAllProfitable(Map<String, BazaarProduct> products,
                                                       FlipperConfig config) {
        return products.values().stream()
                .filter(p -> !BLACKLISTED.contains(p.productId))
                .filter(p -> p.roi() > 0)
                .filter(p -> p.topBuyOrderPrice >= MIN_UNIT_PRICE && p.topSellOrderPrice >= MIN_UNIT_PRICE)
                .filter(p -> p.marginPerUnit() >= MIN_PROFIT_PER_UNIT)
                .filter(p -> p.effectiveDailyVolume() >= 10)
                .sorted(Comparator.comparingDouble((BazaarProduct p) ->
                        p.flipScore(W1, W2, W3, K)).reversed())
                .collect(Collectors.toList());
    }
}
