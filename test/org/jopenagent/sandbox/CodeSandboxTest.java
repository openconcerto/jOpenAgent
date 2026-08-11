package org.jopenagent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Requires {@code --add-modules jdk.jshell} on the JUnit run configuration, like everything else
 * touching {@link CodeSandbox}. Public (unlike the other tests) because JShell snippets must be
 * able to reference the nested {@link PricingSelf} bound as {@code self}.
 */
public class CodeSandboxTest {

    /** Stand-in for a live agent: generated code calls {@code self.unitPriceCents(...)} on it. */
    public static final class PricingSelf {
        public int unitPriceCents(String sku) {
            return 499;
        }
    }

    private CodeSandbox sandbox;

    private CodeSandbox sandbox() {
        if (sandbox == null) {
            sandbox = new CodeSandbox(new PricingSelf(), 5000);
        }
        return sandbox;
    }

    @AfterEach
    void closeSandbox() {
        if (sandbox != null) {
            sandbox.close();
            sandbox = null;
        }
    }

    @Test
    void finalExpressionValueIsReported() {
        SandboxResult result = sandbox().execute(
                "int unitPrice = self.unitPriceCents(\"WIDGET-1\");\nint totalPrice = unitPrice * 3;\ntotalPrice;");
        assertEquals(SandboxResult.Status.OK, result.getStatus());
        assertEquals("1497", result.getLastValue());
    }

    @Test
    void trailingReturnStatementDoesNotClobberLastValue() {
        // JShell compiles a top-level `return x;` as a statement snippet whose event value is ""
        // (not null); it must not overwrite the value of the last real expression.
        SandboxResult result = sandbox().execute(
                "int unitPrice = self.unitPriceCents(\"WIDGET-1\");\nint totalPrice = unitPrice * 3;\nreturn totalPrice;");
        assertEquals(SandboxResult.Status.OK, result.getStatus());
        assertEquals("1497", result.getLastValue());
    }

    @Test
    void trailingVoidStatementDoesNotClobberLastValue() {
        SandboxResult result = sandbox().execute(
                "int total = self.unitPriceCents(\"WIDGET-1\") * 3;\nSystem.out.flush();");
        assertEquals(SandboxResult.Status.OK, result.getStatus());
        assertEquals("1497", result.getLastValue());
    }

    @Test
    void emptyStringExpressionValueIsStillReported() {
        // JShell renders a String value with quotes, so an empty String arrives as "" (two
        // characters) and must survive the statement-event filter.
        SandboxResult result = sandbox().execute("String s = \"\";\ns;");
        assertEquals(SandboxResult.Status.OK, result.getStatus());
        assertEquals("\"\"", result.getLastValue());
    }

    @Test
    void statementsOnlyCodeHasNoLastValue() {
        SandboxResult result = sandbox().execute("System.out.flush();");
        assertEquals(SandboxResult.Status.OK, result.getStatus());
        assertNull(result.getLastValue());
    }
}
