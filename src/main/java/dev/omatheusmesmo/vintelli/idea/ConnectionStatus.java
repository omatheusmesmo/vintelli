package dev.omatheusmesmo.vintelli.idea;

public record ConnectionStatus(ConnectionState state, String detail) {

    public static ConnectionStatus connected() {
        return new ConnectionStatus(ConnectionState.CONNECTED, "IntelliJ IDEA MCP server reachable");
    }

    public static ConnectionStatus disconnected(String detail) {
        return new ConnectionStatus(ConnectionState.DISCONNECTED, detail);
    }

    public static ConnectionStatus unreachable(String detail) {
        return new ConnectionStatus(ConnectionState.UNREACHABLE, detail);
    }

    public static ConnectionStatus authRequired(String detail) {
        return new ConnectionStatus(ConnectionState.AUTH_REQUIRED, detail);
    }

    public static ConnectionStatus consentPending(String detail) {
        return new ConnectionStatus(ConnectionState.CONSENT_PENDING, detail);
    }

    public static ConnectionStatus endpointNotFound(String detail) {
        return new ConnectionStatus(ConnectionState.ENDPOINT_NOT_FOUND, detail);
    }

    public static ConnectionStatus timeout(String detail) {
        return new ConnectionStatus(ConnectionState.TIMEOUT, detail);
    }

    public static ConnectionStatus error(String detail) {
        return new ConnectionStatus(ConnectionState.ERROR, detail);
    }
}
