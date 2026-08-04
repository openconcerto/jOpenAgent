package org.jopenagent.sandbox.remote;

/** Raised on {@link RpcChannel} transport/timeout/remote-error failures. */
public class RpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RpcException(String message) {
        super(message);
    }

    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
