package one.idsstorage.domain;

public class AggregationQuery {
    private String fromIso;
    private String toIso;
    private String interval = "minute";
    private int limit = 1000;

    public String getFromIso() {
        return fromIso;
    }

    public void setFromIso(String fromIso) {
        this.fromIso = fromIso;
    }

    public String getToIso() {
        return toIso;
    }

    public void setToIso(String toIso) {
        this.toIso = toIso;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
