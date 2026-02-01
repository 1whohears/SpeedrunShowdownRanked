package com.onewhohears.srsdranked;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.onewhohears.srsdranked.command.*;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.onewhohears.srsdranked.UtilNetApi.getRequestURL;
import static com.onewhohears.srsdranked.UtilNetApi.handleResponseAsync;

@Plugin(id = "srsdranked", name = "SpeedrunShowdownRanked", version = BuildConstants.VERSION)
public class SpeedrunShowdownRanked {

    public static SpeedrunShowdownRanked SRSDR;

    public static YamlDocument CONFIG;

    public final Logger logger;
    public final ProxyServer proxy;

    private final Map<Integer, GPServerState> gpServerStates = new HashMap<>();
    private final InternalApiServer internalApiServer = new InternalApiServer(this);

    public boolean vetoSeed(@NotNull Player player, int gameId) {
        if (!gpServerStates.containsKey(gameId)) {
            player.sendMessage(errorMsg("Could not veto gameplay server "+gameId+" because that is an invalid id."));
            return false;
        }
        GPServerState state = gpServerStates.get(gameId);
        return state.vetoSeed(this, player);
    }

    public boolean resetGameplaySeed(int id, Consumer<String> debug) {
        if (!gpServerStates.containsKey(id)) {
            debug.accept("Could not reset gameplay server "+id+" because that is an invalid id.");
            return false;
        }
        RegisteredServer gameServer = getGameplayServer(id);
        if (gameServer == null) {
            debug.accept("Could not reset gameplay server "+id+" because it does not exist.");
            return false;
        }
        RegisteredServer lobbyServer = getLobbyServer();
        if (lobbyServer == null) {
            debug.accept("Could not send players to the lobby server because it does not exist.");
            return false;
        }
        gameServer.sendMessage(infoMsg("This seed is being reset." +
                " Players will be temporarily moved to the Lobby."));
        GPServerState state = gpServerStates.get(id);
        for (Player player : gameServer.getPlayersConnected()) {
            player.createConnectionRequest(lobbyServer).connect();
            state.addToRejoinList(player);
        }
        internalApiServer.sendResetSeed(id);
        return true;
    }

    public void joinCreateQueue(@NotNull Player player, @NotNull QueueType type) {
        if (getFromWatchList(player) != null) {
            player.sendMessage(errorMsg("You already joined a queue. You must leave if you want to join a different one!"));
            return;
        }
        GPServerState state = getByType(type);
        if (state == null) {
            state = getFirstEmpty();
            if (state == null) {
                player.sendMessage(errorMsg("There are no gameplay servers available for "+type));
                return;
            }
            state.setQueueType(type);
            final GPServerState gpss = state;
            String requestUrl = getRequestURL("/league/queue/create") + type.getCreateQueueParams();
            handleResponseAsync(requestUrl, this, response -> {
                if (response.has("error")) {
                    player.sendMessage(errorMsg(response.get("error").getAsString()));
                    return;
                }
                JsonObject queue = response.getAsJsonObject("queue");
                int queueId = queue.get("id").getAsInt();
                gpss.setQueueId(queueId);
                gpss.setQueueData(queue);
                resetGameplaySeed(gpss.getLobbyId(), msg -> player.sendMessage(infoMsg(msg)));
                joinQueue(player, gpss);
            });
            return;
        }
        if (state.getQueueId() == -1) {
            player.sendMessage(errorMsg("Queue is loading please wait a few more seconds..."));
            return;
        }
        joinQueue(player, state);
    }

    private void joinQueue(@NotNull Player player, @NotNull GPServerState state) {
        String reqUrl = getRequestURL("/league/queue/join");
        reqUrl += "&mcUUID="+player.getUniqueId()+"&queueId="+state.getQueueId();
        handleResponseAsync(reqUrl, this, response -> {
            if (response.has("error")) {
                player.sendMessage(errorMsg(response.get("error").getAsString()));
                return;
            }
            state.setQueueData(response.getAsJsonObject("queue"));
            String result = response.get("result").getAsString();
            player.sendMessage(infoMsg("Join Queue "+state.getQueueId()+" Result: "+result));
            player.sendMessage(infoMsg("Once the Queue Enters Pre-Game, you must Check In with " +
                    "/check_in_queue "+state.getQueueId()));
            if (result.equals("SUCCESS") || result.equals("ALREADY_JOINED")) {
                state.addUuidToWatchList(player.getUniqueId().toString());
                if (!sendToGameplayServer(player, state.getLobbyId())) {
                    player.sendMessage(errorMsg("Could not send you to the game play server: "+state.getStatus()));
                }
            }
        });
    }

    public static Component errorMsg(String msg) {
        return Component.text(msg, NamedTextColor.RED);
    }

    public static Component infoMsg(String msg) {
        return Component.text(msg, NamedTextColor.YELLOW);
    }

    public static Component specialMsg(String msg) {
        return Component.text(msg, NamedTextColor.LIGHT_PURPLE);
    }

    @Nullable
    private GPServerState getByType(@NotNull QueueType type) {
        for (GPServerState state : gpServerStates.values()) {
            if (state.isGameInProgress()) continue;
            if (state.getQueueType() == type) return state;
        }
        return null;
    }

    @Nullable
    private GPServerState getFirstEmpty() {
        for (GPServerState state : gpServerStates.values()) {
            if (state.isEmpty()) return state;
        }
        return null;
    }

    public void setupGameplayLobby(int lobbyId, int queueId) {
        GPServerState state = gpServerStates.get(lobbyId);
        if (state == null) {
            logger.error("Could not setup gameplay lobby {} because it does not exist.", lobbyId);
            return;
        }
        RegisteredServer gameServer = getGameplayServer(lobbyId);
        if (gameServer == null) {
            logger.error("Could not setup gameplay server {} because it does not exist.", lobbyId);
            return;
        }
        logger.info("Setting up Gameplay Server {} for Queue {}", lobbyId, queueId);
        internalApiServer.sendSetQueue(lobbyId, queueId);
        if (queueId != -1) {
            String reqUrl = getRequestURL("/league/queue/state");
            reqUrl += "&queueId=" + queueId;
            handleResponseAsync(reqUrl, this, response -> {
                if (response.has("error")) {
                    logger.error(response.get("error").getAsString());
                    return;
                }
                JsonObject queueData = response.getAsJsonObject("queue");
                state.setQueueData(queueData);
                state.sendPlayersToServer(this);
            });
        } else {
            state.resolveRejoinList(gameServer);
        }
    }

    public void handleContestantResponse(long contestantId, @NotNull Consumer<JsonObject> responseHandler) {
        String leagueBotURL = getRequestURL("/league/info/contestant");
        leagueBotURL += "&contestantId="+contestantId;
        handleResponseAsync(leagueBotURL, this, responseHandler);
    }

    public void handleContestantResponse(String key, String value, @NotNull Consumer<JsonObject> responseHandler) {
        String leagueBotURL = getRequestURL("/league/info/contestant");
        leagueBotURL += "&contestantDataKey="+key+"&contestantDataValue="+value;
        handleResponseAsync(leagueBotURL, this, responseHandler);
    }

    public void handleStatusResponse(int gameId, String status) {
        GPServerState state = gpServerStates.get(gameId);
        if (state == null) {
            logger.error("Received enable message from gameplay server with invalid id {}", gameId);
            return;
        }
        state.readStatus(status, this);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        CommandManager cmdMng = proxy.getCommandManager();
        cmdMng.register(cmdMng.metaBuilder("reset_seed").plugin(this).build(), ResetSeed.create(this));
        cmdMng.register(cmdMng.metaBuilder("join_queue").plugin(this).build(), JoinQueue.create(this));
        cmdMng.register(cmdMng.metaBuilder("leave_queue").plugin(this).build(), LeaveQueue.create(this));
        cmdMng.register(cmdMng.metaBuilder("check_in_queue").plugin(this).build(), CheckInQueue.create(this));
        cmdMng.register(cmdMng.metaBuilder("check_in").plugin(this).build(), CheckInQueue.create2(this));
        cmdMng.register(cmdMng.metaBuilder("check_out_queue").plugin(this).build(), CheckOutQueue.create(this));
        cmdMng.register(cmdMng.metaBuilder("check_out").plugin(this).build(), CheckOutQueue.create2(this));
        cmdMng.register(cmdMng.metaBuilder("veto").plugin(this).build(), VetoSeed.create(this));
        cmdMng.register(cmdMng.metaBuilder("rejoin").plugin(this).build(), RejoinGame.create(this));
        cmdMng.register(cmdMng.metaBuilder("link_discord").plugin(this).build(), LinkDiscord.create(this));

        try {
            internalApiServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        proxy.getScheduler().buildTask(this, () ->
            gpServerStates.forEach((id, state) -> state.updateState(this))
        ).repeat(2, TimeUnit.SECONDS).schedule();
    }

    @Inject
    public SpeedrunShowdownRanked(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        SRSDR = this;
        this.proxy = proxy;
        this.logger = logger;
        try {
            CONFIG = YamlDocument.create(new File(dataDirectory.toFile(), "config.yml"),
                    getClass().getResourceAsStream("/config.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("file_version"))
                            .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build());
            CONFIG.update();
            CONFIG.save();
            int numGPServers = CONFIG.getInt("gameplay_server_num");
            for (int i = 0; i < numGPServers; ++i) gpServerStates.put(i, new GPServerState(i));
        } catch (IOException e) {
            this.logger.error("Could not create/load plugin config! This plugin will now shutdown!");
            Optional<PluginContainer> container = proxy.getPluginManager().getPlugin("srsdranked");
            container.ifPresent(plugin -> plugin.getExecutorService().shutdown());
        }
        this.logger.info("SRSD Ranked Plugin has initialized!");
    }

    @Subscribe
    public void onConnectedToSever(ServerConnectedEvent event) {
        GPServerState stateFromList = getFromWatchList(event.getPlayer());
        if (stateFromList == null) return;
        String serverName = event.getServer().getServerInfo().getName();
        GPServerState stateFromName = getFromServerName(serverName);
        logger.info("CONNECT {} | {} | {} | {}", event.getPlayer(), serverName, stateFromList, stateFromName);
        if (stateFromName != null && stateFromList.getLobbyId() == stateFromName.getLobbyId()) {
            stateFromName.onPlayerConnect(event.getPlayer());
            return;
        }
        if (stateFromList.isResettingSeed()) return;
        stateFromList.onPlayerDisconnect(this, event.getPlayer());
        proxy.getScheduler().buildTask(this,
                () -> sendToGameplayServer(event.getPlayer(), stateFromList.getLobbyId()))
                        .delay(2, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        GPServerState state = getFromWatchList(event.getPlayer());
        if (state == null) return;
        state.onPlayerDisconnect(this, event.getPlayer());
    }

    @Nullable
    public GPServerState getFromWatchList(@NotNull Player player) {
        for (GPServerState state : gpServerStates.values())
            if (state.isOnWatchList(player))
                return state;
        return null;
    }

    @Nullable
    public GPServerState getFromServerName(String serverName) {
        if (serverName.equals(CONFIG.getString("default_server"))) return null;
        String gamePrefix = CONFIG.getString("gameplay_server");
        if (!serverName.startsWith(gamePrefix)) return null;
        String gameIdStr = serverName.substring(gamePrefix.length());
        int gameId;
        try { gameId = Integer.parseInt(gameIdStr); }
        catch (NumberFormatException e) { return null; }
        if (!gpServerStates.containsKey(gameId)) return null;
        return gpServerStates.get(gameId);
    }

    @Nullable
    public GPServerState getServerStateByQueueId(int queueId) {
        return gpServerStates.values().stream()
                .filter(state -> state.getQueueId() == queueId)
                .findFirst().orElse(null);
    }

    public boolean sendToDefaultServer(@NotNull Player player) {
        String server = CONFIG.getString("default_server");
        return sendPlayerToServer(player, server);
    }

    public boolean sendToGameplayServer(@NotNull Player player, int id) {
        String server = CONFIG.getString("gameplay_server") + id;
        return sendPlayerToServer(player, server);
    }

    public boolean sendPlayerToServer(@NotNull Player player, @NotNull String serverName) {
        Optional<RegisteredServer> server = proxy.getServer(serverName);
        if (server.isEmpty()) return false;
        player.createConnectionRequest(server.get()).connect();
        return true;
    }

    @Nullable
    public RegisteredServer getLobbyServer() {
        Optional<RegisteredServer> server = proxy.getServer(CONFIG.getString("default_server"));
        return server.orElse(null);
    }

    @Nullable
    public RegisteredServer getGameplayServer(int id) {
        Optional<RegisteredServer> server = proxy.getServer(CONFIG.getString("gameplay_server")+id);
        return server.orElse(null);
    }

    public InternalApiServer getInternalApiServer() {
        return internalApiServer;
    }

    public boolean linkDiscordAccount(@NotNull Player player, int linkCode) {
        String leagueBotURL = getRequestURL("/league/link/minecraft/player");
        leagueBotURL += "&mcUUID="+player.getUniqueId()+"&linkCode="+linkCode;

        handleResponseAsync(leagueBotURL, this, response -> {
            if (response.has("error")) {
                player.sendMessage(errorMsg(response.get("error").getAsString()));
                return;
            }
            player.sendMessage(infoMsg(response.get("result").getAsString()));
        });

        return true;
    }
}
