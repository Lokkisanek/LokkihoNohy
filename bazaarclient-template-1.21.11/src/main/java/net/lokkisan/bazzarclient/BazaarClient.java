package net.lokkisan.bazzarclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class BazaarClient {
    // HttpClient pro odesílání dat do Pythonu
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public static int getDecisionFromAI(String itemId, double sellPrice, double buyPrice) {
        try {
            // Sestavení dat pro AI (přesně tak, jak to server.py očekává)
            JsonObject json = new JsonObject();
            json.addProperty("item_id", itemId);
            json.addProperty("sell_price", sellPrice);
            json.addProperty("buy_price", buyPrice);
            json.addProperty("balance", 1000000); // Tady by mohl být tvůj reálný budget
            json.addProperty("has_item", 0);      // 0 = nemám, 1 = mám (pro jednoduchost)

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:5000/predict"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();

            // Pošleme dotaz a počkáme na odpověď
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            // Přečteme akci (0=Nic, 1=Koupit, 2=Prodat)
            JsonObject resJson = JsonParser.parseString(response.body()).getAsJsonObject();
            return resJson.get("action").getAsInt();
            
        } catch (Exception e) {
            // Pokud Python server neběží, mod nebude nic dělat
            return 0; 
        }
    }
}