package com.onewhohears.srsdranked;

public enum QueueType {
    NONE(0, 0, false, false),
    SOLO(2, 1, false, false),
    DUOS(4, 2, false, false),
    TEAM(4, 2, true, true);
    public final int minPlayers, teamSize;
    public final boolean allowLargerTeams, allowOddNum;
    private final String createQueueParams;
    QueueType(int minPlayers, int teamSize, boolean allowLargerTeams, boolean allowOddNum) {
        this.minPlayers = minPlayers;
        this.teamSize = teamSize;
        this.allowLargerTeams = allowLargerTeams;
        this.allowOddNum = allowOddNum;
        this.createQueueParams = "&minPlayers="+minPlayers+"&teamSize="+teamSize
                +"&allowLargerTeams="+allowLargerTeams+"&allowOddNum="+allowOddNum;
    }
    public String getCreateQueueParams() {
        return createQueueParams;
    }
}
