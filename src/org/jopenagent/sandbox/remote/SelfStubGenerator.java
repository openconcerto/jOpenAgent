package org.jopenagent.sandbox.remote;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.jopenagent.agentdoc.MemberScanner;

/**
 * Generates a Java source stub class mirroring an agent's visible ordinary methods (the same set
 * {@link org.jopenagent.tools.ToolRegistry} exposes): sent to the worker process as plain source text
 * for its own JShell to compile, so the worker never needs the real agent class instantiated.
 * Each generated method forwards to {@link RemoteSelfDispatch#invoke} instead of running locally,
 * but keeps the real method's exact name/parameter types/return type, so generated code can still
 * write {@code self.someMethod(x, y)} with ordinary compile-time checking, no dynamic proxy or
 * bytecode generation needed, since JShell already compiles arbitrary source text as its normal
 * mode of operation.
 */
final class SelfStubGenerator {

    private SelfStubGenerator() {
    }

    /**
     * Builds the full source text of a same-named-as-{@code agentClass} stub class: one generated
     * method per {@link MemberScanner#ordinaryVisibleMethods(Class) ordinary visible method} of
     * the real agent (see {@link #appendMethod} for each method's body), wrapped in a class
     * declaration. Sent to the worker's own JShell session verbatim as the {@code declare}
     * request's {@code classSource}.
     */
    static String generateClassSource(Class<?> agentClass) {
        String className = agentClass.getSimpleName();
        String fqcn = agentClass.getCanonicalName();
        StringBuilder sb = new StringBuilder();
        sb.append("public class ").append(className).append(" {\n");
        for (Method method : MemberScanner.ordinaryVisibleMethods(agentClass)) {
            appendMethod(sb, fqcn, method);
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * The companion statement evaluated right after {@link #generateClassSource}, declaring the
     * worker-side {@code self} variable as an instance of the generated stub class, evaluated as a
     * <em>separate</em> {@code jshell.eval()} call from the class source (see
     * {@code SandboxWorkerMain.WorkerRequestHandler#handleDeclare}), since JShell does not reliably
     * commit both a type declaration and a following statement from one combined source string.
     */
    static String declarationStatement(Class<?> agentClass) {
        String className = agentClass.getSimpleName();
        return className + " self = new " + className + "();";
    }

    /**
     * Emits one stub method matching {@code method}'s real name/parameter types/return type. The
     * generated body does not run any real logic; it packs the parameter types, argument values,
     * and parameter names into arrays and forwards them to
     * {@link RemoteSelfDispatch#invoke(Class, String, Class[], Object[], String[])}, which does the
     * actual RPC round trip to the host and binds the JSON result back to {@code returnType}.
     */
    private static void appendMethod(StringBuilder sb, String fqcn, Method method) {
        String returnType = JavaTypeSource.render(method.getGenericReturnType());
        Parameter[] params = method.getParameters();

        sb.append("    public ").append(returnType).append(" ").append(method.getName()).append("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(JavaTypeSource.render(params[i].getParameterizedType())).append(" ").append(params[i].getName());
        }
        sb.append(") {\n");

        sb.append("        Class<?>[] __paramTypes = new Class<?>[] {");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getType().getCanonicalName()).append(".class");
        }
        sb.append("};\n");

        sb.append("        Object[] __args = new Object[] {");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getName());
        }
        sb.append("};\n");

        sb.append("        String[] __names = new String[] {");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(params[i].getName()).append("\"");
        }
        sb.append("};\n");

        sb.append("        Object __result = org.jopenagent.sandbox.remote.RemoteSelfDispatch.invoke(").append(fqcn).append(".class, \"").append(method.getName())
                .append("\", __paramTypes, __args, __names);\n");

        if (!"void".equals(returnType)) {
            sb.append("        return (").append(returnType).append(") __result;\n");
        }
        sb.append("    }\n\n");
    }
}
