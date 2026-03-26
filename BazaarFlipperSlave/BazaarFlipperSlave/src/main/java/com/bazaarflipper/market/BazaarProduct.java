package com.bazaarflipper.market;

public class BazaarProduct {
    public final String productId;
    public final double instaBuyPrice;   // what you pay to insta-buy (= top sell order)
    public final double instaSellPrice;  // what you get to insta-sell (= top buy order)
    public final double topBuyOrderPrice;  // highest buy order in book
    public final double topSellOrderPrice; // lowest sell order in book
    public final long buyVolume;     // current buy order volume
    public final long sellVolume;    // current sell order volume
    public final long buyMovingWeek;   // weekly insta-buy volume
    public final long sellMovingWeek;  // weekly insta-sell volume
    public final int buyOrderCount;    // number of active buy orders
    public final int sellOrderCount;   // number of active sell orders
    public final int nearbyBuyOrders;  // orders within 2% of top buy price
    public final int nearbySellOrders; // orders within 2% of top sell price

    private static final double TAX_RATE = 0.0125;

    public BazaarProduct(String productId, double instaBuyPrice, double instaSellPrice,
                         double topBuyOrderPrice, double topSellOrderPrice,
                         long buyVolume, long sellVolume,
                         long buyMovingWeek, long sellMovingWeek,
                         int buyOrderCount, int sellOrderCount,
                         int nearbyBuyOrders, int nearbySellOrders) {
        this.productId = productId;
        this.instaBuyPrice = instaBuyPrice;
        this.instaSellPrice = instaSellPrice;
        this.topBuyOrderPrice = topBuyOrderPrice;
        this.topSellOrderPrice = topSellOrderPrice;
        this.buyVolume = buyVolume;
        this.sellVolume = sellVolume;
        this.buyMovingWeek = buyMovingWeek;
        this.sellMovingWeek = sellMovingWeek;
        this.buyOrderCount = buyOrderCount;
        this.sellOrderCount = sellOrderCount;
        this.nearbyBuyOrders = nearbyBuyOrders;
        this.nearbySellOrders = nearbySellOrders;
    }

    /** Price we'd place our buy order at (beat top buyer by 0.1) */
    public double ourBuyPrice() {
        return topBuyOrderPrice + 0.1;
    }

    /** Price we'd place our sell order at (undercut top seller by 0.1) */
    public double ourSellPrice() {
        return topSellOrderPrice - 0.1;
    }

    /**
     * ROI = (Ps * (1 - tax) - Pb) / Pb
     * Ps = top sell order price (what we'd undercut)
     * Pb = top buy order price (what we'd outbid)
     */
    public double roi() {
        double pb = ourBuyPrice();
        double ps = ourSellPrice();
        if (pb <= 0) return 0;
        return (ps * (1.0 - TAX_RATE) - pb) / pb;
    }

    /** Margin percentage = ROI * 100 */
    public double marginPct() {
        return roi() * 100.0;
    }

    /** Margin per unit in coins */
    public double marginPerUnit() {
        return ourSellPrice() * (1.0 - TAX_RATE) - ourBuyPrice();
    }

    /**
     * Effective daily volume = min(instabuy/day, instasell/day)
     * An item is only as liquid as its slower side.
     */
    public double effectiveDailyVolume() {
        double buyPerDay = buyMovingWeek / 7.0;
        double sellPerDay = sellMovingWeek / 7.0;
        return Math.min(buyPerDay, sellPerDay);
    }

    /**
     * Competition = average nearby orders on both sides.
     * Orders within ~2% of our price that we'd be competing with.
     */
    public double competition() {
        return (nearbyBuyOrders + nearbySellOrders) / 2.0;
    }

    /**
     * Full flip score using the formula:
     * S = ROI^w1 * log10(Veff)^w2 * (1/(1+cv))^w3 * e^(-k*C)
     *
     * Since we don't have historical price variance, we approximate
     * volatility from the spread between insta prices and order prices.
     */
    public double flipScore(double w1, double w2, double w3, double k) {
        double roiVal = roi();
        if (roiVal <= 0) return 0;

        // Factor 1: ROI
        double f1 = Math.pow(roiVal, w1);

        // Factor 2: Liquidity (log10 of effective daily volume)
        double vEff = effectiveDailyVolume();
        if (vEff < 1) return 0;
        double f2 = Math.pow(Math.log10(vEff), w2);

        // Factor 3: Stability (volatility approximation)
        // Use spread between insta prices as a proxy for volatility
        // cv = |instaBuy - instaSell| / avg(instaBuy, instaSell)
        double avg = (instaBuyPrice + instaSellPrice) / 2.0;
        double spread = Math.abs(instaBuyPrice - instaSellPrice);
        double cv = avg > 0 ? spread / avg : 1.0;
        double f3 = Math.pow(1.0 / (1.0 + cv), w3);

        // Factor 4: Competition penalty
        double c = competition();
        double f4 = Math.exp(-k * c);

        return f1 * f2 * f3 * f4;
    }

    public String displayName() {
        StringBuilder sb = new StringBuilder();
        for (String word : productId.split("_")) {
            if (!sb.isEmpty()) sb.append(" ");
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    public String searchTerm() {
        return displayName();
    }
}
