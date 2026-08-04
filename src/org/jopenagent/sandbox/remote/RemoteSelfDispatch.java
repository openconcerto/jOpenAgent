package org.jopenagent.sandbox.remote;

import java.lang.reflect.Method;

import org.jopenkit.json.JSONObject;

import org.jopenagent.json.ObjectBinder;

/**
 * Static bridge the generated self-stub's methods call into (see {@link SelfStubGenerator}) to
 * reach the worker process's {@link RpcChannel}, installed once by {@link SandboxWorkerMain}
 * right after it connects back to the host. Public because the dynamically-declared stub class
 * (a different, JShell-compiled class) needs to call it by fully-qualified name.
 */
public final class RemoteSelfDispatch {

    private static volatile RpcChannel channel;
    private static volatile long callTimeoutMillis = 30_000L;

    private RemoteSelfDispatch() {
    }

    static void install(RpcChannel channel, long timeoutMillis) {
        RemoteSelfDispatch.channel = channel;
        RemoteSelfDispatch.callTimeoutMillis = timeoutMillis;
    }

    /**
     * Called from a generated stub method. Sends the call to the host (which invokes it for real
     * against the live agent, via {@code org.jopenagent.tools.ToolRegistry}) and binds the JSON result
     * back against the real method's actual declared return type: resolved here via reflection
     * on {@code declaringClass} (loadable on the worker's classpath even though no live instance
     * of it exists in this process), not by trusting the stub's own (possibly erased-to-Object)
     * signature.
     */
    public static Object invoke(Class<?> declaringClass, String methodName, Class<?>[] paramTypes, Object[] argValues, String[] paramNames) {
        JSONObject argsJson = new JSONObject();
        for (int i = 0; i < argValues.length; i++) {
            argsJson.put(paramNames[i], ObjectBinder.toJson(argValues[i]));
        }
        JSONObject callParams = new JSONObject();
        callParams.put("method", methodName);
        callParams.put("arguments", argsJson);

        JSONObject response = channel.request("invokeSelf", callParams, callTimeoutMillis);

        Method realMethod = findMethod(declaringClass, methodName, paramTypes);
        if (realMethod.getReturnType() == void.class) {
            return null;
        }
        Object rawValue = response.has("value") ? response.opt("value") : null;
        return ObjectBinder.bind(rawValue, realMethod.getGenericReturnType());
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        Class<?> current = cls;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, paramTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new RpcException("Cannot resolve method '" + name + "' on " + cls);
    }
}
