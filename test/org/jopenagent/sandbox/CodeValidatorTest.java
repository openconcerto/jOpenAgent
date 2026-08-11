package org.jopenagent.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeValidatorTest {

    @Test
    void allowsOrdinaryCode() {
        CodeValidator.ValidationResult result = CodeValidator.validate("int x = self.ticketBacklog(); System.out.println(x);");
        assertTrue(result.isValid(), result.getViolations().toString());
    }

    @Test
    void blocksRuntimeExec() {
        CodeValidator.ValidationResult result = CodeValidator.validate("Runtime.getRuntime().exec(\"calc\");");
        assertFalse(result.isValid());
    }

    @Test
    void blocksProcessBuilder() {
        CodeValidator.ValidationResult result = CodeValidator.validate("new ProcessBuilder(\"cmd\").start();");
        assertFalse(result.isValid());
    }

    @Test
    void blocksFullyQualifiedFileAccess() {
        CodeValidator.ValidationResult result = CodeValidator.validate("java.io.File f = new java.io.File(\"/etc/passwd\");");
        assertFalse(result.isValid());
    }

    @Test
    void blocksSystemExitButNotSystemOut() {
        assertFalse(CodeValidator.validate("System.exit(0);").isValid());
        assertTrue(CodeValidator.validate("System.out.println(\"hi\");").isValid());
    }

    @Test
    void blocksReflectionPackage() {
        CodeValidator.ValidationResult result = CodeValidator.validate("java.lang.reflect.Method m = null;");
        assertFalse(result.isValid());
    }

    @Test
    void doesNotFlagDenylistedWordsInsideStringLiterals() {
        CodeValidator.ValidationResult result = CodeValidator.validate("String s = \"Runtime and File are just words here\";");
        assertTrue(result.isValid(), result.getViolations().toString());
    }

    @Test
    void blocksQualifiedPrefixEvenWithWhitespaceAroundDots() {
        CodeValidator.ValidationResult result = CodeValidator.validate("java . lang . Runtime . getRuntime ( ) ;");
        assertFalse(result.isValid());
    }

    @Test
    void referencesSelfMethodDetectsDirectCall() {
        assertTrue(CodeValidator.referencesSelfMethod("int t = self.totalPriceViaCode();", "totalPriceViaCode"));
        assertTrue(CodeValidator.referencesSelfMethod("self . totalPriceViaCode ( )", "totalPriceViaCode"));
    }

    @Test
    void referencesSelfMethodIgnoresOtherMethodsAndOtherReceivers() {
        assertFalse(CodeValidator.referencesSelfMethod("int p = self.unitPriceCents(\"X\");", "totalPriceViaCode"));
        assertFalse(CodeValidator.referencesSelfMethod("int t = other.totalPriceViaCode();", "totalPriceViaCode"));
    }

    @Test
    void referencesSelfMethodIgnoresStringLiteralsAndComments() {
        assertFalse(CodeValidator.referencesSelfMethod("String s = \"self.totalPriceViaCode()\";", "totalPriceViaCode"));
        assertFalse(CodeValidator.referencesSelfMethod("// self.totalPriceViaCode()\nint x = 1;", "totalPriceViaCode"));
    }

    @Test
    void doesNotFlagIdentifiersThatMerelyContainBlockedNamesAsSubstrings() {
        CodeValidator.ValidationResult result = CodeValidator.validate("String myFileName = \"data.txt\"; System.out.println(myFileName);");
        // "myFileName" is a distinct identifier token from "File"/"System" and must not match.
        for (String violation : result.getViolations()) {
            assertFalse(violation.contains("myFileName"));
        }
    }
}
