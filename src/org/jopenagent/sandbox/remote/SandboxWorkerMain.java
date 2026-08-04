package org.jopenagent.sandbox.remote;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.sandbox.CodeValidator;
import org.jopenagent.sandbox.JShellScriptRunner;

/**
 * Out-of-process {@code CODE_ACT} worker: connects back to the host over a loopback socket, then
 * serves {@code declare} (install the self-stub, see {@link SelfStubGenerator}) and
 * {@code execute} (run one code snippet) requests via its own local JShell session, the same
 * execution mechanics as the in-process {@link org.jopenagent.sandbox.CodeSandbox}, just living in this
 * separate JVM so a hard kill of the whole process (see
 * {@link org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox}) is a real, clean containment boundary.
 */
public final class SandboxWorkerMain {

    private SandboxWorkerMain() {
    }

    /**
     * Entry point for the worker child JVM, launched by {@link OutOfProcessCodeSandbox#start()}
     * with {@code args[0]} the loopback port to connect back to and {@code args[1]} (optional) the
     * per-call timeout in milliseconds to hand to {@link RemoteSelfDispatch#install}. Connects,
     * installs its own JShell-backed {@link RpcChannel.RequestHandler}, then blocks until the host
     * closes the socket (graceful shutdown) or the process is killed outright (timeout/crash
     * recovery): this method never returns of its own accord.
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long callTimeoutMillis = args.length > 1 ? Long.parseLong(args[1]) : 30_000L;

        Socket socket = new Socket("127.0.0.1", port);
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        JShell jshell = JShell.builder().out(captured).err(captured).executionEngine("local").build();
        addHostClasspath(jshell);

        RpcChannel channel = new RpcChannel(socket, new WorkerRequestHandler(jshell, outBuffer, captured));
        RemoteSelfDispatch.install(channel, callTimeoutMillis);

        // Block for the lifetime of the session; the host tears this process down (gracefully by
        // closing the socket, or forcibly on timeout) rather than this loop ever deciding to exit.
        while (!socket.isClosed()) {
            Thread.sleep(200);
        }
    }

    private static void addHostClasspath(JShell jshell) {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                jshell.addToClasspath(entry);
            }
        }
    }

    /** Serves the two requests the host sends: {@code declare} (install the self-stub) and {@code execute} (run one code snippet), both against this worker's own local {@link JShell} session. */
    private static final class WorkerRequestHandler implements RpcChannel.RequestHandler {
        private final JShell jshell;
        private final ByteArrayOutputStream outBuffer;
        private final PrintStream captured;

        WorkerRequestHandler(JShell jshell, ByteArrayOutputStream outBuffer, PrintStream captured) {
            this.jshell = jshell;
            this.outBuffer = outBuffer;
            this.captured = captured;
        }

        /** Dispatches to {@link #handleDeclare} or {@link #handleExecute} by request method name; anything else is a protocol error. */
        @Override
        public JSONObject handle(String method, JSONObject params) {
            if ("declare".equals(method)) {
                return handleDeclare(params);
            }
            if ("execute".equals(method)) {
                return handleExecute(params);
            }
            throw new RpcException("Unknown worker method: " + method);
        }

        private JSONObject handleDeclare(JSONObject params) {
            // Evaluated as two separate jshell.eval() calls, not one concatenated string: JShell's
            // eval() only reliably returns/commits the *first* top-level snippet it finds in a
            // single call when a type declaration is immediately followed by a statement in the
            // same source text, silently dropping the rest. Splitting them mirrors how a human
            // pastes a class definition then a variable declaration into jshell one at a time.
            evalOrThrow(params.optString("classSource", ""));
            evalOrThrow(params.optString("declaration", ""));
            return new JSONObject();
        }

        private void evalOrThrow(String source) {
            List<SnippetEvent> events = jshell.eval(source);
            for (SnippetEvent event : events) {
                if (event.status() == Snippet.Status.REJECTED) {
                    throw new RpcException("Failed to declare self-stub: " + diagnosticsOf(event));
                }
            }
        }

        private JSONObject handleExecute(JSONObject params) {
            String code = params.optString("code", "");
            JSONObject result = new JSONObject();

            CodeValidator.ValidationResult validation = CodeValidator.validate(code);
            if (!validation.isValid()) {
                result.put("status", "REJECTED");
                result.put("stdout", "");
                JSONArray messages = new JSONArray();
                for (String violation : validation.getViolations()) {
                    messages.add(violation);
                }
                result.put("messages", messages);
                return result;
            }

            outBuffer.reset();
            // See CodeSandbox.execute(): the "local" execution engine shares this JVM's real
            // System.out/System.err, which JShell.Builder.out()/err() does not capture on its own.
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            List<SnippetEvent> events;
            try {
                System.setOut(captured);
                System.setErr(captured);
                events = JShellScriptRunner.evalAll(jshell, code);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            String stdout = outBuffer.toString(StandardCharsets.UTF_8);

            List<String> messages = new ArrayList<>();
            String lastValue = null;
            boolean hasError = false;
            for (SnippetEvent event : events) {
                if (event.status() == Snippet.Status.REJECTED) {
                    hasError = true;
                    messages.add(diagnosticsOf(event));
                } else if (event.exception() != null) {
                    hasError = true;
                    messages.add(describeException(event.exception()));
                } else if (event.value() != null) {
                    lastValue = event.value();
                }
            }

            result.put("status", hasError ? "RUNTIME_ERROR" : "OK");
            result.put("stdout", stdout);
            if (lastValue != null) {
                result.put("value", lastValue);
            }
            JSONArray messagesJson = new JSONArray();
            for (String message : messages) {
                messagesJson.add(message);
            }
            result.put("messages", messagesJson);
            return result;
        }

        private String diagnosticsOf(SnippetEvent event) {
            List<String> texts = jshell.diagnostics(event.snippet()).map(new DiagToString()).collect(Collectors.toList());
            return texts.isEmpty() ? "compile error" : String.join("; ", texts);
        }

        /** Renders one {@link Diag} as the plain-text message included in a compile-error result. */
        private static final class DiagToString implements java.util.function.Function<Diag, String> {
            @Override
            public String apply(Diag diag) {
                return diag.getMessage(null);
            }
        }

        private static String describeException(JShellException e) {
            if (e instanceof EvalException) {
                EvalException evalException = (EvalException) e;
                return evalException.getExceptionClassName() + ": " + evalException.getMessage();
            }
            return e.toString();
        }
    }
}
