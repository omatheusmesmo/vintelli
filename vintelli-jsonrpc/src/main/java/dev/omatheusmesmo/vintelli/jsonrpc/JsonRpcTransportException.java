package dev.omatheusmesmo.vintelli.jsonrpc;

/**
 * Base type for all transport level failures raised by the shared JSON-RPC plumbing.
 *
 * <p>Subclasses separate the three situations callers must tell apart: malformed
 * payloads that can be dropped without losing framing integrity
 * ({@link JsonRpcProtocolException}), error responses produced by the remote peer
 * ({@link JsonRpcRemoteException}), request handler failures before an error response
 * is written ({@link JsonRpcRequestException}), local cancellation
 * ({@link JsonRpcCancelledException}), and a transport that stopped working
 * ({@link JsonRpcConnectionClosedException}).</p>
 */
public abstract sealed class JsonRpcTransportException extends RuntimeException
        permits JsonRpcProtocolException, JsonRpcRemoteException, JsonRpcRequestException,
        JsonRpcCancelledException, JsonRpcConnectionClosedException {

    protected JsonRpcTransportException(String message) {
        super(message);
    }

    protected JsonRpcTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
