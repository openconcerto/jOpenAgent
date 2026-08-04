package org.jopenagent.sandbox.remote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jopenkit.json.JSONObject;

/**
 * A small bidirectional JSON-RPC transport over one {@link Socket}. The piece that makes
 * out-of-process {@code CODE_ACT} possible without giving up {@code self.method(...)} call
 * syntax: the host uses it to send {@code execute}/{@code declare} requests to the worker, and
 * the worker uses the very same channel to send {@code invokeSelf} requests back to the host
 * <em>while a code execution is still in flight</em>, full-duplex, correlated by a per-message
 * {@code id}, exactly like a symmetric peer-to-peer JSON-RPC 2.0 connection (not a strict
 * client/server request-response cycle).
 */
public final class RpcChannel implements AutoCloseable {

    /** Handles an incoming request and returns its result payload (or throws to signal an error response). */
    public interface RequestHandler {
        JSONObject handle(String method, JSONObject params);
    }

    private final Socket socket;
    private final BufferedReader reader;
    private final Writer writer;
    private final RequestHandler handler;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, BlockingQueue<JSONObject>> pending = new ConcurrentHashMap<>();
    private final Thread readerThread;
    private volatile boolean closed;

    /**
     * Wraps an already-connected {@code socket} and immediately starts a daemon reader thread that
     * dispatches every incoming line as either a response to a pending {@link #request} (matched by
     * {@code id}) or an inbound request handed to {@code handler} (run on its own thread, see
     * {@link #onMessage(JSONObject)} for why handling must not block the reader loop itself).
     */
    public RpcChannel(Socket socket, RequestHandler handler) throws IOException {
        this.socket = socket;
        this.handler = handler;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        this.readerThread = new Thread(new ReaderLoop(), "jooa-rpc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /** Sends a request and blocks (up to {@code timeoutMillis}) for the matching response. */
    public JSONObject request(String method, JSONObject params, long timeoutMillis) {
        int id = nextId.getAndIncrement();
        BlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(1);
        pending.put(id, queue);
        try {
            JSONObject message = new JSONObject();
            message.put("type", "request");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params != null ? params : new JSONObject());
            writeLine(message.toString());

            JSONObject response;
            try {
                response = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RpcException("Interrupted waiting for response to '" + method + "'", e);
            }
            if (response == null) {
                throw new RpcException("Timed out waiting for response to '" + method + "' after " + timeoutMillis + "ms");
            }
            if (response.has("error")) {
                throw new RpcException("Remote call '" + method + "' failed: " + response.optString("error", "unknown error"));
            }
            JSONObject result = response.optJSONObject("result");
            return result != null ? result : new JSONObject();
        } finally {
            pending.remove(id);
        }
    }

    private synchronized void writeLine(String json) {
        try {
            writer.write(json);
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            throw new RpcException("Failed writing to RPC channel: " + e.getMessage(), e);
        }
    }

    private void onMessage(JSONObject message) {
        String type = message.optString("type", "");
        if ("response".equals(type)) {
            int id = message.optInt("id", -1);
            BlockingQueue<JSONObject> queue = pending.get(id);
            if (queue != null) {
                queue.offer(message);
            }
            return;
        }
        if ("request".equals(type)) {
            // Handling must happen off the reader thread: if the handler itself issues a nested
            // request over this same channel (exactly what happens when executed code calls
            // self.method(...) mid-execution), it blocks waiting for a response, and that
            // response can only ever be delivered by this reader loop reading the next line. Run
            // it inline and the two would deadlock each other.
            Thread handlerThread = new Thread(new HandleRequestTask(message), "jooa-rpc-handler");
            handlerThread.setDaemon(true);
            handlerThread.start();
        }
    }

    /** Runs one inbound request against {@link #handler} off the reader thread and writes its response (or error) line back. */
    private final class HandleRequestTask implements Runnable {
        private final JSONObject message;

        HandleRequestTask(JSONObject message) {
            this.message = message;
        }

        @Override
        public void run() {
            int id = message.optInt("id", -1);
            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            JSONObject response = new JSONObject();
            response.put("type", "response");
            response.put("id", id);
            try {
                JSONObject result = handler.handle(method, params != null ? params : new JSONObject());
                response.put("result", result != null ? result : new JSONObject());
            } catch (RuntimeException e) {
                response.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
            }
            writeLine(response.toString());
        }
    }

    /** Background loop that reads newline-delimited JSON messages off the socket for the lifetime of the channel and routes each to {@link #onMessage(JSONObject)}. */
    private final class ReaderLoop implements Runnable {
        @Override
        public void run() {
            try {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JSONObject message;
                    try {
                        message = new JSONObject(line);
                    } catch (RuntimeException notJson) {
                        continue;
                    }
                    onMessage(message);
                }
            } catch (IOException ignored) {
                // socket closed; nothing more to read
            }
        }
    }

    /** Stops the reader loop and closes the underlying socket; any in-flight {@link #request} calls then time out rather than hang forever. */
    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort close
        }
    }
}
