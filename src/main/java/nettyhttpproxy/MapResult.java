package nettyhttpproxy;

public class MapResult {

    public final String host;
    public final int port;
    public final Action action;

    public static final MapResult NOT_FOUND = new MapResult(null, 0, Action.NOTFOUND);

    public MapResult(String host, int port, Action action) {
        this.host = host;
        this.port = port;
        this.action = action;
    }

    @Override
    public String toString() {
        return "MapResult{" + "host=" + host + ", port=" + port + ", action=" + action + '}';
    }

    public static enum Action {
        /**
         * Pipe the request, do not cache locally
         */
        PIPE,
        /**
         * Pipe and cache if possible
         */
        CACHE,
        /**
         * Service not mapped
         */
        NOTFOUND,
        /**
         * Answer with debug info
         */
        DEBUG
    }

}
