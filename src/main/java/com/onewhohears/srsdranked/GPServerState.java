package com.onewhohears.srsdranked;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

public class GPServerState {

    public final int id;

    private GPServerStatus status = GPServerStatus.OFFLINE;
    private int queueId = -1;
    private QueueType queueType = QueueType.NONE;
    private JsonObject queueData = new JsonObject();

    public GPServerState(int id) {
        this.id = id;
    }

    public void updateState(@NotNull SpeedrunShowdownRanked plugin) {
        RegisteredServer server = plugin.getGameplayServer(id);
        if (server == null) status = GPServerStatus.OFFLINE;
        else {
            server.ping().whenComplete((s, error) -> {
               if (error != null) setOffline();
            });
        }
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
        this.queueType = QueueType.NONE;
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
    }

    public int getLobbyId() {
        return id;
    }
}
