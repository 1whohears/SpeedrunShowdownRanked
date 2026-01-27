package com.onewhohears.srsdranked;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.CONFIG;

public class UtilNetApi {

    public static final Gson GSON = new Gson();

    public static String getRequestURL(String type) {
        String apikey = CONFIG.getString("league_bot_api_key");
        long guildId = CONFIG.getLong("league_bot_guild_id");
        String leagueName = CONFIG.getString("league_bot_league_name");
        String leagueBotURL = CONFIG.getString("league_bot_url");
        leagueBotURL += type+"?apikey="+apikey+"&guildId="+guildId+"&leagueName="+leagueName;
        return leagueBotURL;
    }

    public static void handleResponseAsync(@NotNull String requestURL,
                                           @NotNull SpeedrunShowdownRanked plugin,
                                           @NotNull Consumer<JsonObject> responseHandler) {
        handleResponseAsync(requestURL, null, plugin, responseHandler);
    }

    public static void handleResponseAsync(@NotNull String requestURL, @Nullable CommandSource sender,
                                           @NotNull SpeedrunShowdownRanked plugin,
                                           @NotNull Consumer<JsonObject> responseHandler) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return getResponse(requestURL, sender, plugin);
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
                    if (sender != null) sender.sendMessage(msg);
                    else plugin.proxy.sendMessage(msg);
                    e.printStackTrace();
                }
            }).schedule();
        });
    }

    @Nullable
    private static String getResponse(String requestURL, @Nullable CommandSource sender,
                                      @NotNull SpeedrunShowdownRanked plugin) {
        String response;
        try {
            URL url = new URL(requestURL);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Content-Type", "application/json");
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            response = getBufferedReader(con);
            con.disconnect();
        } catch (IOException e) {
            plugin.proxy.getScheduler().buildTask(plugin, () -> {
                Component msg = Component.text("FAILED: " + e.getMessage())
                        .color(TextColor.color(0xFF0000));
                if (sender != null) sender.sendMessage(msg);
                else plugin.proxy.sendMessage(msg);
                plugin.logger.error("{} | {}", requestURL, e.getMessage());
                e.printStackTrace();
            }).schedule();
            return null;
        }
        return response;
    }

    private static @NotNull String getBufferedReader(HttpURLConnection con) throws IOException {
        int status = con.getResponseCode();
        Reader streamReader = null;
        if (status > 299) {
            streamReader = new InputStreamReader(con.getErrorStream());
        } else {
            streamReader = new InputStreamReader(con.getInputStream());
        }
        BufferedReader in = new BufferedReader(streamReader);
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();
        return content.toString();
    }

}
