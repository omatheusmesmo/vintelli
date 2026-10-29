package dev.omatheusmesmo.vintelli.jsonrpc;

/**
 * Any single JSON-RPC 2.0 message: a request, a notification, or a response.
 *
 * <p>Batched messages are out of scope: both consumers (LSP and ACP) exchange single
 * messages on the framings implemented here.</p>
 */
public sealed interface JsonRpcMessage
        permits JsonRpcRequest, JsonRpcNotification, JsonRpcResponse {
}
