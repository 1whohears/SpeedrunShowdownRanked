package com.onewhohears.srsdranked;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.*;
import static com.onewhohears.srsdranked.UtilNetApi.getRequestURL;
import static com.onewhohears.srsdranked.UtilNetApi.handleResponseAsync;

public class GPServerState {

    public final int id;

    private final Map<UUID,Long> vetoLoginTimes = new HashMap<>();
    private final Map<UUID,Long> loginTimes = new HashMap<>();
    private final Map<UUID,Long> disconnectTimes = new HashMap<>();
    private final Map<UUID,Long> totalDisconnectedTimes = new HashMap<>();
    private final Map<Long,UUID> discordToMcIds = new HashMap<>();
    private final List<Set<UUID>> vetos = new ArrayList<>();
    private final Set<UUID> readyVotes = new HashSet<>();
    private final Set<Player> rejoinPlayers = new HashSet<>();
    private final Set<String> queuePlayers = new HashSet<>();

    private GPServerStatus status = GPServerStatus.OFFLINE;
    private int queueId = -1;
    private QueueType queueType = QueueType.NONE;
    private QueueState queueState = QueueState.NONE;
    private JsonObject queueData = new JsonObject();
    private int numQueueMembers = 0;
    private long prevTime = 0;
    private boolean isSetResolved = false;

    public GPServerState(int id) {
        this.id = id;
    }

    public void addToRejoinList(@NotNull Player player) {
        rejoinPlayers.add(player);
    }

    public void resolveRejoinList(@NotNull RegisteredServer gameServer) {
        rejoinPlayers.forEach(player -> player.createConnectionRequest(gameServer).connect());
        rejoinPlayers.clear();
    }

    public boolean checkIn(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        SRSDR.logger.info("CHECK IN {}", player.getUsername());
        String reqUrl = getRequestURL("/league/queue/check_in");
        reqUrl += "&mcUUID="+player.getUniqueId()+"&queueId="+queueId;
        handleResponseAsync(reqUrl, plugin, response -> {
            if (response.has("error")) {
                player.sendMessage(errorMsg(response.get("error").getAsString()));
                return;
            }
            long userId = response.get("userId").getAsLong();
            discordToMcIds.put(userId, player.getUniqueId());
            setQueueData(response.getAsJsonObject("queue"));
            String result = response.get("result").getAsString();
            player.sendMessage(infoMsg("Check In Queue "+queueId+" Result: "+result));
            if (isPreGame() && isOnline() && (result.equals("SUCCESS") || result.equals("ALREADY_JOINED"))) {
                if (!plugin.sendToGameplayServer(player, getLobbyId())) {
                    player.sendMessage(errorMsg("Could not send you to the game play server: "+getStatus()));
                }
            }
        });
        return true;
    }

    public boolean checkOut(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        SRSDR.logger.info("CHECK OUT {}", player.getUsername());
        String reqUrl = getRequestURL("/league/queue/check_out");
        reqUrl += "&mcUUID="+player.getUniqueId()+"&queueId="+queueId;
        handleResponseAsync(reqUrl, plugin, response -> {
            if (response.has("error")) {
                player.sendMessage(errorMsg(response.get("error").getAsString()));
                return;
            }
            setQueueData(response.getAsJsonObject("queue"));
            String result = response.get("result").getAsString();
            player.sendMessage(infoMsg("Check Out Queue "+queueId+" Result: "+result));
            plugin.sendToDefaultServer(player);
            readyVotes.remove(player.getUniqueId());
        });
        return true;
    }

    public boolean leave(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        SRSDR.logger.info("LEAVE {}", player.getUsername());
        String reqUrl = getRequestURL("/league/queue/leave");
        reqUrl += "&mcUUID="+player.getUniqueId()+"&queueId="+queueId;
        handleResponseAsync(reqUrl, plugin, response -> {
            if (response.has("error")) {
                player.sendMessage(errorMsg(response.get("error").getAsString()));
                return;
            }
            setQueueData(response.getAsJsonObject("queue"));
            String result = response.get("result").getAsString();
            player.sendMessage(infoMsg("Leave Queue "+queueId+" Result: "+result));
            plugin.sendToDefaultServer(player);
            queuePlayers.remove(player.getUniqueId().toString());
            readyVotes.remove(player.getUniqueId());
        });
        return true;
    }

    private void handlePlayerVeto(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        int highestTier = findHighestTier(player);
        if (highestTier >= numQueueMembers-1) {
            highestTier = numQueueMembers-1;
            registerVeto(player, highestTier+1);
        } else {
            while (!registerVeto(player, highestTier+1)) {
                ++highestTier;
            }
        }
        int TIER = highestTier+1;
        if (vetos.get(highestTier).size() % (highestTier + 1) != 0) {
            player.getCurrentServer().ifPresent(server -> server.getServer().sendMessage(specialMsg(
                    player.getUsername()+" is requesting someone join them in a Tier "+TIER+" Veto!"
            )));
            return;
        }
        player.getCurrentServer().ifPresent(server -> server.getServer().sendMessage(specialMsg(
                player.getUsername()+" is using their Tier "+TIER+" Veto to Reset the Seed!"
        )));
        vetoLoginTimes.clear();
        readyVotes.clear();
        if (highestTier >= numQueueMembers-1) vetos.get(highestTier).clear();
        plugin.resetGameplaySeed(id, msg -> player.sendMessage(errorMsg(msg)));
    }

    private boolean registerVeto(@NotNull Player player, int tier) {
        if (vetos.size() < tier) vetos.add(new HashSet<>());
        return vetos.get(tier-1).add(player.getUniqueId());
    }

    private int findHighestTier(@NotNull Player player) {
        if (vetos.isEmpty()) return 0;
        for (int i = 0; i < vetos.size(); ++i)
            if (!vetos.get(i).contains(player.getUniqueId()))
                return i;
        return vetos.size()-1;
    }

    public boolean vetoSeed(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        if (queueId == -1) {
            player.sendMessage(errorMsg("Cannot Veto. This Lobby does not have a Queue ID set."));
            return false;
        }
        long vetoTime = CONFIG.getLong("veto_time");
        long loginTime = vetoLoginTimes.computeIfAbsent(player.getUniqueId(), uuid -> System.currentTimeMillis());
        long diff = System.currentTimeMillis() - loginTime;
        if (diff > vetoTime * 1000) {
            player.sendMessage(errorMsg("You must Veto the seed with "+vetoTime+" seconds after logging in!" +
                    " You logged in "+((int)diff/1000)+" seconds ago!"));
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
            plugin.handleContestantResponse("mcUUID", player.getUniqueId().toString(), res -> {
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
        plugin.proxy.getScheduler().buildTask(plugin, () -> tickTotalDisconnectTimes(plugin)).schedule();
    }

    public boolean voteReady(@NotNull Player player) {
        if (!isPreGame()) {
            player.sendMessage(errorMsg("You can only vote ready while in Pre Game Phase!"));
            return false;
        }
        boolean added = readyVotes.add(player.getUniqueId());
        RegisteredServer server = SRSDR.getGameplayServer(getLobbyId());
        if (server == null) return false;
        if (added) {
            server.sendMessage(specialMsg(player.getUsername()+" voted ready to start the match now! "
                    +readyVotes.size()+"/"+numQueueMembers));
        } else {
            player.sendMessage(errorMsg("You already voted to start the match!"));
        }
        if (numQueueMembers >= getQueueType().minPlayers && readyVotes.size() >= numQueueMembers) {
            String requestUrl = getRequestURL("/league/queue/action");
            requestUrl += "&action=create_set&queueId="+getQueueId();
            handleResponseAsync(requestUrl, SRSDR, response -> {
                if (response.has("error")) {
                    player.sendMessage(errorMsg(response.get("error").getAsString()));
                    return;
                }
                JsonObject queue = response.getAsJsonObject("queue");
                setQueueData(queue);
                String result = response.get("result").getAsString();
                server.sendMessage(infoMsg("Create Set Result: "+result));
            });
        }
        return true;
    }

    public void cancelMatch(@NotNull SpeedrunShowdownRanked plugin, @Nullable UUID disconnectedUUID) {
        RegisteredServer server = plugin.getGameplayServer(getLobbyId());
        if (server == null) return;
        plugin.getInternalApiServer().sendCancelSet(getLobbyId());
        server.sendMessage(infoMsg("The match has been canceled!"));
        if (disconnectedUUID != null) {
            long loginTime = loginTimes.get(disconnectedUUID);
            long disconnectedTime = disconnectTimes.get(disconnectedUUID);
            long totalDisconnectedTime = totalDisconnectedTimes.get(disconnectedUUID);
            long currentTime = System.currentTimeMillis();
            plugin.logger.warn("MATCH CANCELED: Player with UUID {} was logged off for too long! " +
                    "Time Since: Login {} Disconnect {} Total Offline Time {}",
                    disconnectedUUID, (currentTime - loginTime) / 1000,
                    (currentTime - disconnectedTime) / 1000, totalDisconnectedTime / 1000);
            server.sendMessage(specialMsg("A player has been logged off for "
                    + (totalDisconnectedTime / 1000) + " seconds!"));
        }
        if (isSetResolved) {
            int setId = queueData.get("resolvedSetId").getAsInt();
            String reqUrl = getRequestURL("/league/set/cancel");
            reqUrl += "&setId="+setId;
            if (disconnectedUUID != null) reqUrl += "&penaltyMcUUIDList="+disconnectedUUID;
            handleResponseAsync(reqUrl, plugin, response -> {
                if (response.has("error")) {
                    String error = response.get("error").getAsString();
                    server.sendMessage(errorMsg(error));
                    plugin.logger.error(error);
                    return;
                }
                String result = response.get("result").getAsString();
                server.sendMessage(infoMsg(result));
                plugin.logger.info(result);
            });
        }
        resetQueue();
    }

    public void resetQueue() {
        if (queueId != -1) {
            SRSDR.logger.info("GAME SERVER {} {} {} {} RESETTING QUEUE", getLobbyId(), getQueueId(), status, queueState);
        }
        queueId = -1;
        queueType = QueueType.NONE;
        queueState = QueueState.NONE;
        queueData = new JsonObject();
        vetoLoginTimes.clear();
        vetos.clear();
        readyVotes.clear();
        rejoinPlayers.clear();
        queuePlayers.clear();
        loginTimes.clear();
        disconnectTimes.clear();
        totalDisconnectedTimes.clear();
        discordToMcIds.clear();
    }

    public void onPlayerConnect(@NotNull Player player) {
        SRSDR.logger.info("PLAYER CONNECT {} {}", getLobbyId(), player);
        loginTimes.put(player.getUniqueId(), System.currentTimeMillis());
        vetoLoginTimes.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        checkIn(SRSDR, player);
    }

    public void onPlayerDisconnect(@NotNull SpeedrunShowdownRanked plugin, @NotNull Player player) {
        SRSDR.logger.info("PLAYER DISCONNECT {} {}", getLobbyId(), player);
        if (!isOnWatchList(player)) return;
        disconnectTimes.put(player.getUniqueId(), System.currentTimeMillis());
        checkOut(plugin, player);
    }

    private void tickTotalDisconnectTimes(@NotNull SpeedrunShowdownRanked plugin) {
        if (!isGameInProgress() || isResettingSeed()) return;
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - prevTime;
        long cancelMatchTimeout = CONFIG.getInt("disconnect_timeout") * 1000;
        for (Map.Entry<UUID, Long> entry : disconnectTimes.entrySet()) {
            UUID uuid = entry.getKey();
            long disconnectTime = entry.getValue();
            if (!loginTimes.containsKey(uuid)) continue;
            long loginTime = loginTimes.get(uuid);
            if (currentTime > disconnectTime && disconnectTime > loginTime) {
                totalDisconnectedTimes.computeIfAbsent(uuid, id -> timeDiff);
                totalDisconnectedTimes.computeIfPresent(uuid, (id, time) -> time + timeDiff);
                if (totalDisconnectedTimes.get(uuid) > cancelMatchTimeout) {
                    totalDisconnectedTimeExceeded(plugin, uuid);
                    break;
                }
            }
        }
        prevTime = currentTime;
    }

    private void totalDisconnectedTimeExceeded(@NotNull SpeedrunShowdownRanked plugin, @NotNull UUID uuid) {
        cancelMatch(plugin, uuid);
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
        GPServerStatus prev = this.status;
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
        if (wasOffline && this.status == GPServerStatus.ONLINE) {
            plugin.setupGameplayLobby(id, queueId);
        }
        if (this.status != prev)
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
        QueueState next = readQueueState(queueData.get("queueState").getAsString());
        if (next != queueState) {
            if (next == QueueState.PREGAME) {
                vetoLoginTimes.clear();
                sendPlayersToServer(SRSDR);
                sendMessage(specialMsg("PREGAME has started! All enrolled players must check in and can Veto seeds!"));
                RegisteredServer server = SRSDR.getGameplayServer(getLobbyId());
                if (server != null) {
                    for (Player player : server.getPlayersConnected()) {
                        checkIn(SRSDR, player);
                    }
                }
            } else if (next == QueueState.PREGAME_SUBS) {
                sendPlayersToServer(SRSDR);
                sendMessage(specialMsg("PREGAME SUBS has started! If you check in after the phase you loose veto rights!"));
            }
        }
        queueState = next;
        JsonArray members = queueData.get("members").getAsJsonArray();
        numQueueMembers = members.size();
        boolean wasSetResolved = isSetResolved;
        isSetResolved = queueData.get("resolved").getAsBoolean();
        if (!wasSetResolved && isSetResolved) {
            for (int i = 0; i < members.size(); ++i) {
                JsonObject member = members.get(i).getAsJsonObject();
                String queueStatus = member.get("queueStatus").getAsString();
                if (queueStatus.equals("CHECKED_IN")) continue;
                long id = Long.parseLong(member.get("id").getAsString());
                UUID uuid = discordToMcIds.get(id);
                if (uuid == null) continue;
                queuePlayers.remove(uuid.toString());
            }
        }
        if (queueState == QueueState.CLOSED && !isSetResolved) resetQueue();
    }

    public void sendMessage(Component msg) {
        RegisteredServer gameServer = SRSDR.getGameplayServer(getLobbyId());
        if (gameServer != null) gameServer.sendMessage(msg);
    }

    public void sendPlayersToServer(@NotNull SpeedrunShowdownRanked plugin) {
        RegisteredServer gameServer = plugin.getGameplayServer(getLobbyId());
        if (gameServer == null) return;
        JsonArray members = queueData.getAsJsonArray("members");
        for (int i = 0; i < members.size(); ++i) {
            JsonObject member = members.get(i).getAsJsonObject();
            long id = member.get("id").getAsLong();
            plugin.handleContestantResponse(id, res -> {
                if (res.has("error")) {
                    plugin.logger.error(res.get("error").getAsString());
                    return;
                }
                JsonObject memberData = res.getAsJsonObject("contestant");
                JsonObject extraData = memberData.getAsJsonObject("extra_data");
                if (!extraData.has("mcUUID")) {
                    plugin.logger.error("Could not send member {} to the gameplay server" +
                            " because their discord account is not linked!", id);
                    return;
                }
                String mcUUIDStr = extraData.get("mcUUID").getAsString();
                addUuidToWatchList(mcUUIDStr);
                Optional<Player> player = plugin.proxy.getPlayer(UUID.fromString(mcUUIDStr));
                player.ifPresent(p -> {
                    p.createConnectionRequest(gameServer).connect();
                    p.sendMessage(infoMsg("You have "+CONFIG.getLong("veto_time")+" seconds to " +
                            "Veto the seed with /veto"));
                });
            });
        }
    }

    public int getLobbyId() {
        return id;
    }

    public void addUuidToWatchList(String mcUUIDStr) {
        queuePlayers.add(mcUUIDStr);
    }

    public boolean isOnWatchList(Player player) {
        return queuePlayers.contains(player.getUniqueId().toString());
    }

    public void sendStatus(@NotNull Player player) {
        player.sendMessage(specialMsg("Game Lobby "+getLobbyId()
                +" | Status "+getStatus().name()
                +" | State "+getQueueState().name()
                +" | Type "+getQueueType().name()
        ));
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

    public boolean isPreGame() {
        return queueState == QueueState.PREGAME || queueState == QueueState.PREGAME_SUBS;
    }

    public boolean isOnline() {
        return status == GPServerStatus.ONLINE;
    }

    @Override
    public String toString() {
        return "SERVER_STATE:"+id+":"+status+":"+queueId+":"+queueState;
    }
}
