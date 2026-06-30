package com.onewhohears.srsdranked;

public enum QueueType {
    NONE(0, 0, false, false, false),
    SOLO(2, 1, false, false, false),
    DUOS(4, 2, false, false, false),
    TEAM(4, 2, true, false, false),
    CASUAL(2, 2, true, true, true);
    public final int minPlayers, teamSize;
    public final boolean allowLargerTeams, allowOddNum, customTeams;
    private final String createQueueParams;
    QueueType(int minPlayers, int teamSize, boolean allowLargerTeams, boolean allowOddNum, boolean customTeams) {
        this.minPlayers = minPlayers;
        this.teamSize = teamSize;
        this.allowLargerTeams = allowLargerTeams;
        this.allowOddNum = allowOddNum;
        this.customTeams = customTeams;
        String params = "&minPlayers="+minPlayers+"&teamSize="+teamSize
                +"&allowLargerTeams="+allowLargerTeams+"&allowOddNum="+allowOddNum;
        if (this.customTeams) {
            params += "&ifEnoughPlayersAutoStart=false&subRequestTime=0&timeoutTime=600";
        }
        this.createQueueParams = params;
    }
    public String getCreateQueueParams() {
        return createQueueParams;
    }
    public boolean isNone() {
        return this == NONE;
    }
    public boolean isCasual() {
        return this == CASUAL;
    }
    public boolean isRanked() {
        return !isNone() && !isCasual();
    }
}
