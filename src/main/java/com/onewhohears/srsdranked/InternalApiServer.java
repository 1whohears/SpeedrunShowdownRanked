package com.onewhohears.srsdranked;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.CONFIG;
import static com.onewhohears.srsdranked.UtilNetApi.GSON;

public class InternalApiServer {

    private HttpServer server;
    private final SpeedrunShowdownRanked plugin;

    public void sendResetSeed(int id) {
        String url = getRequestURL("seed_reset", id);
        String jsonBody = "{\"do_it\":\"now\"}";
        handlePostAsync(url, jsonBody,
                response -> plugin.logger.info("Sent Reset Seed to Game Server {}", id));
    }

    public void sendSetQueue(int id, int queueId) {
        String url = getRequestURL("set_queue", id);
        String jsonBody = "{\"queueId\":\""+queueId+"\"}";
        handlePostAsync(url, jsonBody,
                response -> plugin.logger.info("Sent Set Queue to {} to Game Server {}", queueId, id));
    }

    public InternalApiServer(SpeedrunShowdownRanked plugin) {
        this.plugin = plugin;
    }

    public void start() throws IOException {
        int port = CONFIG.getInt("velocity_port");

        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/status", this::handleStatus);
        server.createContext("/ping", this::handlePing);

        server.setExecutor(null);
        server.start();

        plugin.logger.info("API started on :{}", port);
    }

    private void handlePing(HttpExchange ex) throws IOException {
        reply(ex, 200, "velocity plugin ok");
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("X-Auth");
        if (!key.equals(CONFIG.getString("pterodactyl_api_key"))) {
            plugin.logger.warn("Reset Failed Cause Bad Key {}", key);
            ex.sendResponseHeaders(401, -1);
            return;
        }
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            ex.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes());
        JsonObject response = GSON.fromJson(body, JsonObject.class);

        if (!response.has("status")) {
            reply(ex, 400, "Missing status");
            return;
        }
        String status = response.get("status").getAsString();
        if (!response.has("gameId")) {
            reply(ex, 400, "Missing gameId");
            return;
        }
        int gameId = response.get("gameId").getAsInt();

        plugin.proxy.getScheduler().buildTask(plugin, () -> plugin.handleStatusResponse(gameId, status)).schedule();

        reply(ex, 200, "{\"result\":\"Set Status for Game "+gameId+" to "+status+"\"}");
    }

    private void reply(HttpExchange ex, int code, String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private String getRequestURL(String type, int gameId) {
        String host = CONFIG.getString("velocity_ip");
        int port = CONFIG.getIntList("gameplay_server_api_ports").get(gameId);
        String url = host+":"+port+"/"+type;
        plugin.logger.info("CREATED URL: {}", url);
        return url;
    }

    private void handlePostAsync(String requestURL, String jsonBody,
                                 @NotNull Consumer<JsonObject> responseHandler) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return post(requestURL, jsonBody);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(responseStr -> {
            plugin.proxy.getScheduler().buildTask(plugin, () -> {
                if (responseStr == null) return;
                try {
                    JsonObject response = GSON.fromJson(responseStr, JsonObject.class);
                    responseHandler.accept(response);
                } catch (Exception e) {
                    plugin.logger.error("FAILED TO HANDLE RESPONSE: {}\n{}", e.getMessage(), responseStr);
                    Component msg = Component.text("FAILED TO HANDLE RESPONSE: " + e.getMessage())
                            .color(TextColor.color(0xFF0000));
                    plugin.proxy.sendMessage(msg);
                    e.printStackTrace();
                }
            });
        });
    }

    @Nullable
    private String post(String requestURL, String jsonBody) {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestURL))
                .header("X-Auth", CONFIG.getString("pterodactyl_api_key"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code > 299) {
                Component msg = Component.text("Failed: " + code)
                        .color(TextColor.color(0xFF0000));
                plugin.proxy.sendMessage(msg);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            plugin.proxy.getScheduler().buildTask(plugin, () -> {
                Component msg = Component.text("Failed: " + e.getMessage())
                        .color(TextColor.color(0xFF0000));
                plugin.proxy.sendMessage(msg);
                e.printStackTrace();
            });
            e.printStackTrace();
            return null;
        }
    }
}

