package com.bazaarflipper.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BazaarApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long FETCH_INTERVAL_SEC = 30;

    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, BazaarProduct> products = new ConcurrentHashMap<>();
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
    private volatile String apiKey = "";
    private volatile long lastFetchMs = 0;
    private volatile boolean lastFetchSuccess = false;

    public BazaarApi() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BazaarFlipper-API");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::fetchData, 0, FETCH_INTERVAL_SEC, TimeUnit.SECONDS);
        LOGGER.info("[BazaarFlipper] API fetcher started (interval={}s)", FETCH_INTERVAL_SEC);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public void setApiKey(String key) {
        this.apiKey = key != null ? key.trim() : "";
    }

    private void fetchData() {
        if (!fetchInFlight.compareAndSet(false, true)) return;
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(BAZAAR_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            if (!apiKey.isEmpty()) {
                reqBuilder.header("API-Key", apiKey);
            }

            HttpResponse<String> resp = http.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                parseResponse(resp.body());
                lastFetchSuccess = true;
                lastFetchMs = System.currentTimeMillis();
                LOGGER.info("[BazaarFlipper] API fetch OK, {} products loaded", products.size());
            } else {
                LOGGER.warn("[BazaarFlipper] API returned HTTP {}", resp.statusCode());
                lastFetchSuccess = false;
            }
        } catch (Exception e) {
            LOGGER.warn("[BazaarFlipper] API fetch failed: {}", e.getMessage());
            lastFetchSuccess = false;
        } finally {
            fetchInFlight.set(false);
        }
    }

    private void parseResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return;
            if (!root.has("products")) return;

            JsonObject prods = root.getAsJsonObject("products");
            for (Map.Entry<String, JsonElement> entry : prods.entrySet()) {
                String id = entry.getKey();
                JsonObject p = entry.getValue().getAsJsonObject();
                if (!p.has("quick_status")) continue;

                JsonObject qs = p.getAsJsonObject("quick_status");

                double instaBuy = qs.has("buyPrice") ? qs.get("buyPrice").getAsDouble() : 0;
                double instaSell = qs.has("sellPrice") ? qs.get("sellPrice").getAsDouble() : 0;
                long buyVol = qs.has("buyVolume") ? qs.get("buyVolume").getAsLong() : 0;
                long sellVol = qs.has("sellVolume") ? qs.get("sellVolume").getAsLong() : 0;
                long buyMovingWeek = qs.has("buyMovingWeek") ? qs.get("buyMovingWeek").getAsLong() : 0;
                long sellMovingWeek = qs.has("sellMovingWeek") ? qs.get("sellMovingWeek").getAsLong() : 0;
                int buyOrders = qs.has("buyOrders") ? qs.get("buyOrders").getAsInt() : 0;
                int sellOrders = qs.has("sellOrders") ? qs.get("sellOrders").getAsInt() : 0;

                // Top order prices from order book
                double topBuyOrder = instaSell; // what instant sellers get = top buy order
                double topSellOrder = instaBuy; // what instant buyers pay = top sell order

                // Count nearby orders (within 2% of top price) for competition metric
                int nearbyBuy = 0;
                int nearbySell = 0;

                if (p.has("buy_summary")) {
                    var buyArr = p.getAsJsonArray("buy_summary");
                    if (!buyArr.isEmpty()) {
                        topBuyOrder = buyArr.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
                        double threshold = topBuyOrder * 0.98;
                        for (var el : buyArr) {
                            double price = el.getAsJsonObject().get("pricePerUnit").getAsDouble();
                            if (price >= threshold) nearbyBuy++;
                            else break;
                        }
                    }
                }
                if (p.has("sell_summary")) {
                    var sellArr = p.getAsJsonArray("sell_summary");
                    if (!sellArr.isEmpty()) {
                        topSellOrder = sellArr.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
                        double threshold = topSellOrder * 1.02;
                        for (var el : sellArr) {
                            double price = el.getAsJsonObject().get("pricePerUnit").getAsDouble();
                            if (price <= threshold) nearbySell++;
                            else break;
                        }
                    }
                }

                products.put(id, new BazaarProduct(id, instaBuy, instaSell,
                        topBuyOrder, topSellOrder, buyVol, sellVol,
                        buyMovingWeek, sellMovingWeek,
                        buyOrders, sellOrders,
                        nearbyBuy, nearbySell));
            }
        } catch (Exception e) {
            LOGGER.error("[BazaarFlipper] Failed to parse API response: {}", e.getMessage());
        }
    }

    public BazaarProduct getProduct(String productId) {
        return products.get(productId);
    }

    public Map<String, BazaarProduct> getAllProducts() {
        return products;
    }

    public boolean hasData() {
        return !products.isEmpty();
    }

    public boolean isLastFetchSuccess() {
        return lastFetchSuccess;
    }

    public long getLastFetchMs() {
        return lastFetchMs;
    }
}
