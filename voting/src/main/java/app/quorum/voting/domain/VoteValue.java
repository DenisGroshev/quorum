package app.quorum.voting.domain;

public enum VoteValue {
    LIKE(1),
    DISLIKE(0),
    SUPERLIKE(2);

    private final int points;

    VoteValue(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
