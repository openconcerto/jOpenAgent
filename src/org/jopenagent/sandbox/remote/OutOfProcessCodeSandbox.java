package org.jopenagent.sandbox.remote;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.core.Agent;
import org.jopenagent.json.ObjectBinder;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.sandbox.CodeExecutor;
import org.jopenagent.sandbox.SandboxException;
import org.jopenagent.sandbox.SandboxResult;
import org.jopenagent.tools.ToolRegistry;
import org.jopenagent.util.ProcessLaunch;

/**
 * Out-of-process {@code CODE_ACT} backend: launches {@link SandboxWorkerMain} as a genuinely
 * separate child JVM (its own heap, via {@code -Xmx}), and executes generated code there instead
 * of in the host process. {@code self.method(...)} calls made by that code are forwarded back to
 * the host over an {@link RpcChannel} and dispatched against the real, live agent via the same
 * {@link ToolRegistry} that backs {@code TOOL_CALLING}, see {@link SelfStubGenerator} for how a
 * plain Java stub class (not a dynamic proxy) makes this transparent to generated code.
 *
 * <p>This is what makes a timeout a <b>real</b> containment measure rather than a best-effort one:
 * {@link CodeExecutor} in-process ({@link org.jopenagent.sandbox.CodeSandbox}) can only ask JShell to
 * stop, which cannot preempt a tight infinite loop; here, a timeout kills the entire child
 * process ({@link Process#destroyForcibly()}), which always works. On timeout (or any other
 * transport failure), the dead worker is discarded and transparently relaunched on the next call
 *, mirroring NOOA's own out-of-process worker, which likewise restarts on timeout/crash.
 */
public final class OutOfProcessCodeSandbox implements CodeExecutor {

    private static final long STARTUP_TIMEOUT_MILLIS = 20_000L;
    private static final int DEFAULT_HEAP_MB = 256;

    private final Agent agent;
    private final ToolRegistry toolRegistry;
    private final long callTimeoutMillis;
    private final int heapMb;

    private ServerSocket serverSocket;
    private Process workerProcess;
    private RpcChannel channel;
    private WorkerOutputCapture outputCapture;
    private boolean alive;

    /** Convenience overload using the {@value #DEFAULT_HEAP_MB}MB default child-JVM heap cap. */
    public OutOfProcessCodeSandbox(Agent agent, long callTimeoutMillis) {
        this(agent, callTimeoutMillis, DEFAULT_HEAP_MB);
    }

    /**
     * Launches the worker process and blocks until it has connected back and accepted the
     * generated self-stub (see {@link #start()}): construction itself can therefore take up to
     * {@link #STARTUP_TIMEOUT_MILLIS}, since starting a JVM and compiling the stub in it is not
     * instantaneous.
     *
     * @param agent            the live agent whose visible methods generated code may call as {@code self.method(...)}
     * @param callTimeoutMillis per-{@link #execute(String)} wall-clock budget enforced by killing the worker process outright
     * @param heapMb           {@code -Xmx} cap passed to the child JVM
     */
    public OutOfProcessCodeSandbox(Agent agent, long callTimeoutMillis, int heapMb) {
        this.agent = agent;
        this.toolRegistry = new ToolRegistry(agent);
        this.callTimeoutMillis = callTimeoutMillis;
        this.heapMb = heapMb;
        start();
    }

    private void start() {
        try {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new SandboxException("Failed to open sandbox coordination socket: " + e.getMessage(), e);
        }
        int port = serverSocket.getLocalPort();

        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("--add-modules");
        command.add("jdk.jshell");
        command.add("-Xmx" + heapMb + "m");
        command.add("-cp");
        command.add(System.getProperty("java.class.path", ""));
        command.add(SandboxWorkerMain.class.getName());
        command.add(String.valueOf(port));
        command.add(String.valueOf(callTimeoutMillis));

        try {
            ProcessBuilder builder = new ProcessBuilder(ProcessLaunch.wrapForWindowsIfNeeded(command));
            builder.redirectErrorStream(true);
            this.workerProcess = builder.start();
        } catch (IOException e) {
            throw new SandboxException("Failed to launch sandbox worker process: " + e.getMessage(), e);
        }
        this.outputCapture = new WorkerOutputCapture(workerProcess.getInputStream());
        Thread captureThread = new Thread(outputCapture, "jooa-sandbox-worker-output");
        captureThread.setDaemon(true);
        captureThread.start();

        Socket socket;
        try {
            serverSocket.setSoTimeout((int) STARTUP_TIMEOUT_MILLIS);
            socket = serverSocket.accept();
        } catch (IOException e) {
            workerProcess.destroyForcibly();
            throw new SandboxException(
                    "Sandbox worker did not connect within " + STARTUP_TIMEOUT_MILLIS + "ms: " + e.getMessage() + "\nWorker output so far:\n" + outputCapture.text(), e);
        }

        try {
            this.channel = new RpcChannel(socket, new InvokeSelfHandler());
        } catch (IOException e) {
            workerProcess.destroyForcibly();
            throw new SandboxException("Failed to set up sandbox RPC channel: " + e.getMessage(), e);
        }

        String classSource = SelfStubGenerator.generateClassSource(agent.getClass());
        String declaration = SelfStubGenerator.declarationStatement(agent.getClass());
        JSONObject declareParams = new JSONObject();
        declareParams.put("classSource", classSource);
        declareParams.put("declaration", declaration);
        try {
            channel.request("declare", declareParams, STARTUP_TIMEOUT_MILLIS);
        } catch (RpcException e) {
            workerProcess.destroyForcibly();
            throw new SandboxException("Sandbox worker rejected the self-stub declaration: " + e.getMessage() + "\nGenerated stub:\n" + classSource + "\n" + declaration, e);
        }
        this.alive = true;
    }

    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    /**
     * Sends {@code code} to the worker over {@link RpcChannel} as an {@code execute} request and
     * waits up to {@code callTimeoutMillis} for the result. A dead worker (from a previous timeout
     * or crash) is transparently relaunched first via {@link #restart()}; if this call itself times
     * out or the transport otherwise fails, the worker process is force-killed
     * ({@link #killWorker()}) and a {@link SandboxResult.Status#TIMED_OUT} result is returned, the
     * next call restarts it again.
     */
    @Override
    public SandboxResult execute(String code) {
        if (!alive) {
            restart();
        }
        JSONObject params = new JSONObject();
        params.put("code", code);
        JSONObject response;
        try {
            response = channel.request("execute", params, callTimeoutMillis);
        } catch (RpcException e) {
            killWorker();
            return SandboxResult.timedOut(callTimeoutMillis);
        }
        return toSandboxResult(response);
    }

    private SandboxResult toSandboxResult(JSONObject response) {
        String status = response.optString("status", "RUNTIME_ERROR");
        String stdout = response.optString("stdout", "");
        List<String> messages = new ArrayList<>();
        JSONArray messagesJson = response.optJSONArray("messages");
        if (messagesJson != null) {
            for (int i = 0; i < messagesJson.size(); i++) {
                messages.add(messagesJson.getString(i));
            }
        }
        if ("REJECTED".equals(status)) {
            return SandboxResult.rejected(messages);
        }
        if ("RUNTIME_ERROR".equals(status)) {
            return SandboxResult.runtimeError(stdout, messages);
        }
        String value = response.has("value") ? response.optString("value", null) : null;
        return SandboxResult.ok(stdout, value);
    }

    private void killWorker() {
        alive = false;
        if (channel != null) {
            channel.close();
        }
        if (workerProcess != null) {
            workerProcess.destroyForcibly();
            try {
                workerProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        }
    }

    private void restart() {
        killWorker();
        start();
    }

    /**
     * Handles {@code invokeSelf} requests the worker sends back over the same channel while a
     * code execution is in flight: looks up and invokes the named method on the real agent via
     * {@link ToolRegistry} (the same dispatch path {@code TOOL_CALLING} uses), then serializes the
     * result back to JSON for {@link RemoteSelfDispatch#invoke} on the worker side to bind against
     * the real return type.
     */
    private final class InvokeSelfHandler implements RpcChannel.RequestHandler {
        @Override
        public JSONObject handle(String method, JSONObject params) {
            if (!"invokeSelf".equals(method)) {
                throw new RpcException("Unknown host method: " + method);
            }
            String methodName = params.optString("method", "");
            JSONObject arguments = params.optJSONObject("arguments");
            Object toolResult = toolRegistry.invoke(new ToolCall(UUID.randomUUID().toString(), methodName, arguments != null ? arguments : new JSONObject()));
            JSONObject response = new JSONObject();
            response.put("value", ObjectBinder.toJson(toolResult));
            return response;
        }
    }

    /** Drains the worker process's redirected stdout/stderr on a background thread so its pipe never fills up and blocks the child; buffered text is surfaced in startup-failure error messages. */
    private static final class WorkerOutputCapture implements Runnable {
        private final InputStream input;
        private final StringBuilder buffer = new StringBuilder();

        WorkerOutputCapture(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // worker process closed its output
            }
        }

        String text() {
            return buffer.toString();
        }
    }

    /** Kills the worker process and closes its coordination socket; safe to call even if the worker is already dead. */
    @Override
    public void close() {
        killWorker();
    }
}
