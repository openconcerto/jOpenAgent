package org.jopenagent.json;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

/**
 * Generates a JSON-Schema-shaped description (as an org.jopenkit.json.JSONObject) for an
 * arbitrary Java {@link Type}: records, POJOs, List/Set/array, Map, Optional, enums,
 * primitives/wrappers/String.
 *
 * <p>This schema is embedded directly in generation-method prompts (so the LLM knows the exact
 * shape it must return) and can also be handed to an {@code LlmClient} as a provider-native
 * structured-output / tool-parameter schema. Scalar/enum/collection return types are wrapped
 * under a top-level {@code "value"} field so the schema root is always a JSON object, the same
 * constraint NOOA applies for OpenAI/Azure Responses API compatibility (see
 * {@link TypeUtils#requiresRootWrapping(Type)}).
 */
public final class SchemaGenerator {

    private SchemaGenerator() {
    }

    /** Full schema for a @Generative method's declared return type, including root-wrapping if needed. */
    public static JSONObject forReturnType(Type type) {
        if (TypeUtils.requiresRootWrapping(type)) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "object");
            JSONObject properties = new JSONObject();
            properties.put(valueFieldName(), describe(type, new HashSet<Class<?>>()));
            wrapper.put("properties", properties);
            JSONArray required = new JSONArray();
            required.add(valueFieldName());
            wrapper.put("required", required);
            return wrapper;
        }
        Object schema = describe(type, new HashSet<Class<?>>());
        if (schema instanceof JSONObject) {
            return (JSONObject) schema;
        }
        // Defensive: should not happen since non-wrapped types are always object-shaped.
        JSONObject fallback = new JSONObject();
        fallback.put("type", "object");
        return fallback;
    }

    /** Name of the synthetic wrapper field ({@code "value"}) used when {@link TypeUtils#requiresRootWrapping(Type)} is true; must match {@code ObjectBinder}'s own wrapper field constant. */
    public static String valueFieldName() {
        return "value";
    }

    /**
     * Object schema describing a method's parameters by name: used for tools-via-self and
     * CodeAct's {@code return_result} tool. Requires the "-parameters" compiler flag so real
     * parameter names are retained at runtime.
     */
    public static JSONObject forMethodParameters(Method method) {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (Parameter parameter : method.getParameters()) {
            if (!parameter.isNamePresent()) {
                throw new BindingException("Parameter names are not retained for " + method + ". Enable 'Store information about method "
                        + "parameters (-parameters)' in Project Properties > Java Compiler.");
            }
            properties.put(parameter.getName(), describe(parameter.getParameterizedType(), new HashSet<Class<?>>()));
            if (parameter.getType().isPrimitive() || !TypeUtils.isOptional(parameter.getType())) {
                required.add(parameter.getName());
            }
        }
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Object describe(Type type, Set<Class<?>> inProgress) {
        Class<?> raw = TypeUtils.rawClass(type);

        if (TypeUtils.isOptional(raw)) {
            return describe(TypeUtils.firstTypeArgument(type), inProgress);
        }
        if (raw == String.class || raw == CharSequence.class || raw == Character.class || raw == char.class) {
            return scalar("string");
        }
        if (raw == Boolean.class || raw == boolean.class) {
            return scalar("boolean");
        }
        if (raw == Integer.class || raw == int.class || raw == Long.class || raw == long.class || raw == Short.class || raw == short.class || raw == Byte.class || raw == byte.class) {
            return scalar("integer");
        }
        if (raw == Double.class || raw == double.class || raw == Float.class || raw == float.class) {
            return scalar("number");
        }
        if (raw.isEnum()) {
            JSONObject schema = scalar("string");
            JSONArray values = new JSONArray();
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            schema.put("enum", values);
            return schema;
        }
        if (raw.isArray() || TypeUtils.isList(raw) || TypeUtils.isSet(raw)) {
            JSONObject schema = new JSONObject();
            schema.put("type", "array");
            Type elementType = raw.isArray() ? raw.getComponentType() : TypeUtils.firstTypeArgument(type);
            schema.put("items", (Object) describe(elementType, inProgress));
            return schema;
        }
        if (TypeUtils.isMap(raw)) {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("additionalProperties", (Object) describe(TypeUtils.secondTypeArgument(type), inProgress));
            return schema;
        }
        if (raw == Object.class) {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            return schema;
        }
        if (raw.isRecord()) {
            if (inProgress.contains(raw)) {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("description", "(recursive reference to " + raw.getSimpleName() + ")");
                return schema;
            }
            Set<Class<?>> nested = new HashSet<>(inProgress);
            nested.add(raw);
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONArray required = new JSONArray();
            for (RecordComponent component : raw.getRecordComponents()) {
                properties.put(component.getName(), (Object) describe(component.getGenericType(), nested));
                if (component.getType().isPrimitive() || !TypeUtils.isOptional(component.getType())) {
                    required.add(component.getName());
                }
            }
            schema.put("properties", properties);
            schema.put("required", required);
            return schema;
        }
        // Plain POJO: describe from its instance fields.
        if (inProgress.contains(raw)) {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("description", "(recursive reference to " + raw.getSimpleName() + ")");
            return schema;
        }
        Set<Class<?>> nested = new HashSet<>(inProgress);
        nested.add(raw);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (Field field : allInstanceFields(raw)) {
            properties.put(field.getName(), (Object) describe(field.getGenericType(), nested));
            if (field.getType().isPrimitive() || !TypeUtils.isOptional(field.getType())) {
                required.add(field.getName());
            }
        }
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static JSONObject scalar(String jsonType) {
        JSONObject schema = new JSONObject();
        schema.put("type", jsonType);
        return schema;
    }

    private static List<Field> allInstanceFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
