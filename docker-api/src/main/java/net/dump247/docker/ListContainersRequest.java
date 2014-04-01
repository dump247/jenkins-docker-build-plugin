package net.dump247.docker;

/** Request to list the containers. */
public class ListContainersRequest {
    private int _limit;

    public int getLimit() {
        return _limit;
    }

    public void setLimit(final int limit) {
        _limit = limit;
    }

    public ListContainersRequest withLimit(final int limit) {
        setLimit(limit);
        return this;
    }
}
