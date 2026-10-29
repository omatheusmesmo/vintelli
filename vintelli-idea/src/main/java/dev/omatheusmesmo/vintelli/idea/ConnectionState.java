package dev.omatheusmesmo.vintelli.idea;

public enum ConnectionState {
    CONNECTED,
    DISCONNECTED,
    UNREACHABLE,
    AUTH_REQUIRED,
    CONSENT_PENDING,
    ENDPOINT_NOT_FOUND,
    TIMEOUT,
    ERROR
}
