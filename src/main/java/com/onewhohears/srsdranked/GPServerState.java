package com.onewhohears.srsdranked;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.*;
import static com.onewhohears.srsdranked.UtilNetApi.getRequestURL;
import static com.onewhohears.srsdranked.UtilNetApi.handleResponseAsync;

public class GPServerState {

    public final int id;

    private final Map<UUID,Long> loginTimes = new HashMap<>();
    private final List<Set<UUID>> vetos = new ArrayList<>();

    private GPServerStatus status = GPServerStatus.OFFLINE;
    private int queueId = -1;
    private QueueType queueType = QueueType.NONE;
    private QueueState queueState = QueueState.NONE;
    private JsonObject queueData = new JsonObject();

    public GPServerState(int id) {
        this.id = id;
    }

    private void handlePlayerVeto(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        int highestTier = findHighestTier(player);
        registerVeto(player, highestTier+1);
        if (vetos.get(highestTier).size() % (highestTier + 1) != 0) {
            player.getCurrentServer().ifPresent(server -> server.getServer().sendMessage(infoMsg(
                    player.getUsername()+" is requesting someone join them in a Tier "+(highestTier+1)+" Veto!"
            )));
            return;
        }
        player.getCurrentServer().ifPresent(server -> server.getServer().sendMessage(infoMsg(
                player.getUsername()+" is using their Tier "+(highestTier+1)+" Veto to Reset the Seed!"
        )));
        plugin.resetGameplaySeed(id, msg -> player.sendMessage(errorMsg(msg)));
        loginTimes.clear();
    }

    private boolean registerVeto(@NotNull Player player, int tier) {
        if (vetos.size() < tier) vetos.add(new HashSet<>());
        return vetos.get(tier-1).add(player.getUniqueId());
    }

    private int findHighestTier(@NotNull Player player) {
        for (int i = 0; i < vetos.size(); ++i)
            if (!vetos.get(i).contains(player.getUniqueId()))
                return i;
        return 0;
    }

    public boolean vetoSeed(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        if (queueId == -1) {
            player.sendMessage(errorMsg("Cannot Veto. This Lobby does not have a Queue ID set."));
            return false;
        }
        long vetoTime = CONFIG.getLong("veto_time") * 1000;
        long loginTime = loginTimes.computeIfAbsent(player.identity().uuid(), uuid -> System.currentTimeMillis());
        if (System.currentTimeMillis() - loginTime > vetoTime) {
            player.sendMessage(errorMsg("You must Veto the seed with "+vetoTime+" seconds after logging in!"));
            return false;
        }
        String reqUrl = getRequestURL("/league/queue/state")+"&queueId="+queueId;
        handleResponseAsync(reqUrl, plugin, response -> {
            if (response.has("error")) {
                player.sendMessage(errorMsg(response.get("error").getAsString()));
                return;
            }
            JsonObject queueData = response.getAsJsonObject("queue");
            setQueueData(queueData);
            if (!(queueState == QueueState.PREGAME || queueState == QueueState.PREGAME_SUBS)) {
                player.sendMessage(errorMsg("Can only veto the seed during PREGAME"));
                return;
            }
            plugin.handleContestantResponse("mcUUID", player.identity().uuid().toString(), res -> {
                if (res.has("error")) {
                    player.sendMessage(errorMsg(res.get("error").getAsString()));
                    return;
                }
                JsonObject conData = res.getAsJsonObject("contestant");
                long discordId = conData.get("id").getAsLong();
                JsonArray members = queueData.getAsJsonArray("members");
                JsonObject memberData = null;
                for (int i = 0; i < members.size(); ++i) {
                    memberData = members.get(i).getAsJsonObject();
                    if (memberData.get("id").getAsLong() == discordId) break;
                    memberData = null;
                }
                if (memberData == null) {
                    player.sendMessage(errorMsg("You have not Joined Queue "+queueId));
                    return;
                }
                String queueStatus = memberData.get("queueStatus").getAsString();
                if (queueStatus.equals("CHECKED_IN_SUB")) {
                    player.sendMessage(errorMsg("Cannot Veto the Seed because you are a Sub!"));
                    return;
                }
                if (!queueStatus.equals("CHECKED_IN")) {
                    player.sendMessage(errorMsg("Cannot Veto the Seed because you are Not Checked In!"));
                    return;
                }
                handlePlayerVeto(plugin, player);
            });
        });
        return true;
    }

    public void updateState(@NotNull SpeedrunShowdownRanked plugin) {
        if (queueId != -1) {
            String reqUrl = getRequestURL("/league/queue/state")+"&queueId="+queueId;
            handleResponseAsync(reqUrl, plugin, response -> {
                if (response.has("error")) {
                    plugin.logger.error(response.get("error").getAsString());
                    return;
                }
                JsonObject queueData = response.getAsJsonObject("queue");
                setQueueData(queueData);
            });
        } else resetQueue();
        RegisteredServer server = plugin.getGameplayServer(id);
        if (server == null) status = GPServerStatus.OFFLINE;
        else {
            server.ping().whenComplete((s, error) -> {
               if (error != null) setOffline();
            });
        }
    }

    private void resetQueue() {
        queueId = -1;
        queueState = QueueState.NONE;
        queueData = new JsonObject();
        loginTimes.clear();
        vetos.clear();
    }

    public void onPlayerConnect(@NotNull Player player) {
        loginTimes.putIfAbsent(player.identity().uuid(), System.currentTimeMillis());
    }

    public boolean canAnyPlayerJoin() {
        return status == GPServerStatus.ONLINE || status == GPServerStatus.GAME_FINISHED;
    }

    public boolean isGameInProgress() {
        return status == GPServerStatus.GAME_IN_PROGRESS;
    }

    public boolean isOffline() {
        return status == GPServerStatus.OFFLINE;
    }

    public GPServerStatus getStatus() {
        return status;
    }

    public int getQueueId() {
        return queueId;
    }

    public void setQueueId(int queueId) {
        this.queueId = queueId;
    }

    public QueueType getQueueType() {
        return queueType;
    }

    public void setQueueType(QueueType queueType) {
        this.queueType = queueType;
    }

    public void readStatus(@NotNull String status, @NotNull SpeedrunShowdownRanked plugin) {
        boolean wasOffline = isResettingSeed();
        switch (status) {
            case "READY": {
                setOnline();
                break;
            }
            case "RESETTING_SEED": {
                setResettingSeed();
                break;
            }
            case "IN_PROGRESS": {
                setGameInProgress();
                break;
            }
            case "FINISHED": {
                setGameFinished();
                break;
            }
            default: {
                setOffline();
                break;
            }
        }
        if (wasOffline && this.status == GPServerStatus.ONLINE && queueId != -1) {
            plugin.setupGameplayLobby(id, queueId);
        }
        plugin.logger.info("Received Status {} for Game Server {} with Queue {}", this.status.name(), id, queueId);
    }

    private void setOffline() {
        if (status != GPServerStatus.RESETTING_SEED)
            this.status = GPServerStatus.OFFLINE;
    }

    private void setOnline() {
        this.status = GPServerStatus.ONLINE;
    }

    public void setResettingSeed() {
        this.status = GPServerStatus.RESETTING_SEED;
    }

    public void setGameInProgress() {
        this.status = GPServerStatus.GAME_IN_PROGRESS;
    }

    public void setGameFinished() {
        this.status = GPServerStatus.GAME_FINISHED;
        resetQueue();
    }

    public boolean isResettingSeed() {
        return this.status == GPServerStatus.RESETTING_SEED;
    }

    public boolean isEmpty() {
        return queueId == -1;
    }

    public JsonObject getQueueData() {
        return queueData;
    }

    public void setQueueData(JsonObject queueData) {
        this.queueData = queueData;
        queueState = readQueueState(queueData.get("queueState").getAsString());
        if (queueState == QueueState.CLOSED) resetQueue();
    }

    public int getLobbyId() {
        return id;
    }

    public enum QueueState {
        NONE,
        ENROLL,
        FINAL_ENROLL_TICK,
        PREGAME,
        PREGAME_SUBS,
        FINAL_PREGAME_TICK,
        CLOSED
    }

    public static QueueState readQueueState(String name) {
        return QueueState.valueOf(name);
    }

    public QueueState getQueueState() {
        return queueState;
    }
}
