package net.lokkisan.bazzarclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class BazaarClient {
    // OPRAVA: Vynucení HTTP/1.1 zabrání chybě "Unsupported upgrade request" v Pythonu
    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) 
            .connectTimeout(Duration.ofMillis(500))
            .build();

    public static int getDecisionFromAI(String itemId) {
        try {
            // Sestavení čistého JSONu
            String jsonPayload = "{\"item_id\": \"" + itemId + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8000/predict"))
                    .header("Content-Type", "application/json") // Zásadní pro FastAPI
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            // Pokud server vrátí chybu (např. 422), vypíšeme to do konzole Minecraftu
            if (response.statusCode() != 200) {
                System.out.println("[BazaarClient] Python vrátil chybu: " + response.statusCode());
                return 0;
            }

            JsonObject res = JsonParser.parseString(response.body()).getAsJsonObject();
            return res.get("action").getAsInt();
        } catch (Exception e) { 
            return 0; 
        }
    }
}