package org.jopenagent.sandbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Static source-level denylist for code submitted to {@link CodeSandbox}. This is a tokenizer
 * (string/char/comment-aware, so denied words inside string literals don't false-positive) that
 * extracts dotted identifier chains (types, qualified names) and rejects any that reference a
 * blocked package prefix or a blocked simple type name.
 *
 * <p><b>This is defense-in-depth against accidental foot-guns, not a security boundary</b>, the
 * same honest caveat NOOA states for its own AST-based {@code UnifiedCodeValidator}
 * (nooa/src/nooa/runtime/code_validator.py): a static text/AST check cannot stop determined
 * abuse (reflection tricks, {@code Class.forName} with a computed string, etc.). Real isolation
 * must come from the OS/container/VM the JVM runs in: the same conclusion NOOA reaches, which is
 * why its own default execution path is guardrails-only and real containment is an opt-in
 * separate process. jOpenAgent does not attempt to reimplement NOOA's Linux-only seccomp/Landlock
 * worker (those syscalls have no Windows equivalent, and this project targets Windows), so no
 * out-of-process sandbox is provided here; see the design notes for the full discussion.
 */
public final class CodeValidator {

    private static final Set<String> BLOCKED_SIMPLE_NAMES = new HashSet<>(Arrays.asList("Runtime", "ProcessBuilder", "ProcessHandle", "File", "Files", "Paths",
            "FileWriter", "FileReader", "FileOutputStream", "FileInputStream", "RandomAccessFile", "Socket", "ServerSocket", "DatagramSocket", "URLClassLoader",
            "ClassLoader", "Unsafe", "ScriptEngine", "ScriptEngineManager", "SecurityManager"));

    // Note: java.lang.System is NOT blocked wholesale (System.out.println is a normal, expected way
    // for generated code to report progress), only its dangerous static entry points are.
    private static final List<String> BLOCKED_QUALIFIED_PREFIXES = Arrays.asList("java.lang.reflect", "java.lang.instrument", "java.lang.Runtime",
            "java.lang.ProcessBuilder", "java.lang.ProcessHandle", "java.net", "javax.net", "java.nio.file", "java.io.File", "java.io.RandomAccessFile", "sun.",
            "jdk.internal", "com.sun.", "javax.tools", "javax.script", "System.exit", "System.load", "System.loadLibrary", "System.setSecurityManager",
            "System.setOut", "System.setErr", "System.setIn");

    private CodeValidator() {
    }

    /**
     * Tokenizes {@code source} into dotted identifier chains (skipping string/char literals and
     * comments) and reports every chain that matches a blocked package prefix or a blocked simple
     * type name. An empty {@link ValidationResult#getViolations()} means no denylisted identifier
     * was found: not a guarantee the code is safe, only that it didn't trip this guardrail.
     */
    public static ValidationResult validate(String source) {
        List<String> violations = new ArrayList<>();
        List<List<String>> chains = extractDottedChains(source);
        for (List<String> chain : chains) {
            String joined = String.join(".", chain);
            for (String prefix : BLOCKED_QUALIFIED_PREFIXES) {
                if (joined.startsWith(prefix)) {
                    violations.add("Use of blocked package/class '" + joined + "' is not allowed in sandboxed code.");
                }
            }
            for (String part : chain) {
                if (BLOCKED_SIMPLE_NAMES.contains(part)) {
                    violations.add("Use of blocked identifier '" + part + "' (in '" + joined + "') is not allowed in sandboxed code.");
                }
            }
        }
        return new ValidationResult(violations);
    }

    private static List<List<String>> extractDottedChains(String source) {
        List<List<String>> chains = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int i = 0;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '"') {
                i = skipQuoted(source, i, '"');
                flush(chains, current);
                continue;
            }
            if (c == '\'') {
                i = skipQuoted(source, i, '\'');
                flush(chains, current);
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
                flush(chains, current);
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i);
                flush(chains, current);
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                i++;
                while (i < len && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                current.add(source.substring(start, i));
                continue;
            }
            if (c == '.') {
                i++;
                continue;
            }
            flush(chains, current);
            i++;
        }
        flush(chains, current);
        return chains;
    }

    private static void flush(List<List<String>> chains, List<String> current) {
        if (!current.isEmpty()) {
            chains.add(new ArrayList<>(current));
            current.clear();
        }
    }

    private static int skipQuoted(String s, int i, char quote) {
        int len = s.length();
        i++;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipLineComment(String s, int i) {
        int len = s.length();
        while (i < len && s.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String s, int i) {
        int len = s.length();
        i += 2;
        while (i + 1 < len && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
            i++;
        }
        return Math.min(i + 2, len);
    }

    /** The verdict from one {@link #validate(String)} call: either no violations, or the list of human-readable reasons the code was rejected. */
    public static final class ValidationResult {
        private final List<String> violations;

        ValidationResult(List<String> violations) {
            this.violations = violations;
        }

        public boolean isValid() {
            return violations.isEmpty();
        }

        public List<String> getViolations() {
            return violations;
        }
    }
}
