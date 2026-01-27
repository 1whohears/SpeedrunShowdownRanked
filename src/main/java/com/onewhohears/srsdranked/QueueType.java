package com.onewhohears.srsdranked;

public enum QueueType {
    NONE(""),
    SOLO("&minPlayers=2&teamSize=1&allowLargerTeams=false&allowOddNum=false"),
    DUOS("&minPlayers=4&teamSize=2&allowLargerTeams=false&allowOddNum=false"),
    TEAM("&minPlayers=4&teamSize=2&allowLargerTeams=true&allowOddNum=true");
    private final String createQueueParams;
    QueueType(String createQueueParams) {
        this.createQueueParams = createQueueParams;
    }
    public String getCreateQueueParams() {
        return createQueueParams;
    }
}
