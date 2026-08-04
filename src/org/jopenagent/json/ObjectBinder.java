package org.jopenagent.json;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jopenkit.json.JSON;
import org.jopenkit.json.JSONException;
import org.jopenkit.json.JSONObject;
import org.jopenkit.json.JSONArray;

/**
 * Reflection-based binding between org.jopenkit.json trees (JSONObject/JSONArray/scalars) and
 * arbitrary Java types: records, plain POJOs (no-arg constructor + fields), List/Set/array,
 * Map, Optional, enums and primitives/wrappers/String.
 *
 * <p>This is jOpenAgent's structured-output layer: it replaces the automatic Pydantic-model binding
 * NOOA gets for free from Python type hints. There is no per-class registration step required 
 * unlike org.jopenkit.json's {@code TypeAdapter} registry, binding here is fully automatic from
 * a {@link Type} (including generics), which is what arbitrary {@code @Generative} return types
 * need.
 *
 * <p>Every failure throws {@link BindingException} with a message written to be re-sent to the
 * LLM verbatim as retry-correction feedback (field name, expected shape, actual value).
 */
public final class ObjectBinder {

    private static final String WRAPPER_FIELD = "value";

    private ObjectBinder() {
    }

    // ------------------------------------------------------------------
    // Java -> JSON
    // ------------------------------------------------------------------

    /** Converts an arbitrary Java value into an org.jopenkit.json tree (JSONObject/JSONArray/scalar). */
    public static Object toJson(Object value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Double || value instanceof Float
                || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Optional<?>) {
            Optional<?> opt = (Optional<?>) value;
            return opt.isPresent() ? toJson(opt.get()) : JSONObject.NULL;
        }
        if (value instanceof Map<?, ?>) {
            JSONObject obj = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                obj.put(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return obj;
        }
        if (value instanceof Collection<?>) {
            JSONArray arr = new JSONArray();
            for (Object element : (Collection<?>) value) {
                arr.add(toJson(element));
            }
            return arr;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            JSONArray arr = new JSONArray(length);
            for (int i = 0; i < length; i++) {
                arr.add(toJson(Array.get(value, i)));
            }
            return arr;
        }
        Class<?> cls = value.getClass();
        if (cls.isRecord()) {
            return recordToJson(value, cls);
        }
        return pojoToJson(value, cls);
    }

    private static Object recordToJson(Object value, Class<?> cls) {
        JSONObject obj = new JSONObject();
        for (RecordComponent component : cls.getRecordComponents()) {
            try {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                obj.put(component.getName(), toJson(accessor.invoke(value)));
            } catch (ReflectiveOperationException e) {
                throw new BindingException("Failed to read component '" + component.getName() + "' of " + cls.getName(), e);
            }
        }
        return obj;
    }

    private static Object pojoToJson(Object value, Class<?> cls) {
        JSONObject obj = new JSONObject();
        for (Field field : allInstanceFields(cls)) {
            try {
                field.setAccessible(true);
                obj.put(field.getName(), toJson(field.get(value)));
            } catch (IllegalAccessException e) {
                throw new BindingException("Failed to read field '" + field.getName() + "' of " + cls.getName(), e);
            }
        }
        return obj;
    }

    // ------------------------------------------------------------------
    // JSON -> Java
    // ------------------------------------------------------------------

    /**
     * Binds a raw LLM text response to {@code declaredReturnType}. Handles markdown code-fence
     * stripping and the object-root-wrapping convention (scalars/lists/enums are expected under a
     * {@code "value"} field, exactly like records/POJOs/maps are expected at the JSON root) so
     * every provider can be asked for an object-rooted JSON schema.
     */
    public static <T> T bindResponse(String rawText, Type declaredReturnType) {
        return bindWrapped(parseJsonObject(rawText), declaredReturnType);
    }

    /**
     * Binds an already-parsed JSON object to {@code declaredReturnType}, applying the same
     * object-root-wrapping convention as {@link #bindResponse(String, Type)}. Used for tool-call
     * arguments (e.g. CodeAct's {@code return_result}), which arrive pre-parsed rather than as text.
     */
    public static <T> T bindWrapped(JSONObject root, Type declaredReturnType) {
        if (TypeUtils.requiresRootWrapping(declaredReturnType)) {
            if (!root.has(WRAPPER_FIELD)) {
                throw new BindingException(
                        "Expected the JSON object to have a '" + WRAPPER_FIELD + "' field wrapping the result, e.g. {\"" + WRAPPER_FIELD + "\": ...}. Got: " + root);
            }
            return bind(root.opt(WRAPPER_FIELD), declaredReturnType);
        }
        return bind(root, declaredReturnType);
    }

    /**
     * Parses {@code rawText} (after {@link #extractJsonObjectText(String)} cleanup) into a
     * {@link JSONObject}, wrapping any parse failure or non-object top-level value in a
     * {@link BindingException} whose message is suitable for retry-correction feedback to the LLM.
     */
    public static JSONObject parseJsonObject(String rawText) {
        String jsonText = extractJsonObjectText(rawText);
        Object parsed;
        try {
            parsed = JSON.parse(jsonText);
        } catch (JSONException e) {
            throw new BindingException("Response is not valid JSON: " + e.getMessage() + "\nRaw text was: " + truncate(rawText, 2000), e);
        }
        if (!(parsed instanceof JSONObject)) {
            throw new BindingException("Expected a JSON object at the top level, got: " + describe(parsed));
        }
        return (JSONObject) parsed;
    }

    /** Strips ``` / ```json markdown fences and trims surrounding prose down to the outermost {...}. */
    public static String extractJsonObjectText(String rawText) {
        String text = rawText.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /** Binds a parsed JSON value (JSONObject/JSONArray/scalar/JSONObject.NULL) to a Java type. */
    @SuppressWarnings("unchecked")
    public static <T> T bind(Object json, Type targetType) {
        Class<?> raw = TypeUtils.rawClass(targetType);
        Object normalized = (json == JSONObject.NULL) ? null : json;

        if (normalized == null) {
            if (raw.isPrimitive()) {
                throw new BindingException("Expected a value of type " + raw.getSimpleName() + " but got null/missing");
            }
            if (TypeUtils.isOptional(raw)) {
                return (T) Optional.empty();
            }
            return null;
        }

        if (TypeUtils.isOptional(raw)) {
            return (T) Optional.ofNullable(bind(normalized, TypeUtils.firstTypeArgument(targetType)));
        }
        if (raw == Object.class) {
            return (T) toPlainJava(normalized);
        }
        if (raw == String.class || raw == CharSequence.class) {
            return (T) coerceString(normalized);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return (T) coerceBoolean(normalized);
        }
        if (raw == int.class || raw == Integer.class) {
            return (T) Integer.valueOf(coerceLong(normalized).intValue());
        }
        if (raw == long.class || raw == Long.class) {
            return (T) coerceLong(normalized);
        }
        if (raw == double.class || raw == Double.class) {
            return (T) coerceDouble(normalized);
        }
        if (raw == float.class || raw == Float.class) {
            return (T) Float.valueOf(coerceDouble(normalized).floatValue());
        }
        if (raw == short.class || raw == Short.class) {
            return (T) Short.valueOf(coerceLong(normalized).shortValue());
        }
        if (raw == byte.class || raw == Byte.class) {
            return (T) Byte.valueOf(coerceLong(normalized).byteValue());
        }
        if (raw == char.class || raw == Character.class) {
            String s = coerceString(normalized);
            if (s.length() != 1) {
                throw new BindingException("Expected a single character but got: " + describe(normalized));
            }
            return (T) Character.valueOf(s.charAt(0));
        }
        if (raw.isEnum()) {
            return (T) coerceEnum(raw, normalized);
        }
        if (raw.isArray() || TypeUtils.isList(raw) || TypeUtils.isSet(raw)) {
            return (T) bindCollection(raw, targetType, normalized);
        }
        if (TypeUtils.isMap(raw)) {
            return (T) bindMap(targetType, normalized);
        }
        if (raw.isRecord()) {
            return (T) bindRecord(raw, normalized);
        }
        return (T) bindPojo(raw, normalized);
    }

    private static Object bindRecord(Class<?> raw, Object json) {
        if (!(json instanceof JSONObject)) {
            throw new BindingException("Expected a JSON object for " + raw.getSimpleName() + " but got: " + describe(json));
        }
        JSONObject obj = (JSONObject) json;
        RecordComponent[] components = raw.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            String name = component.getName();
            if (!obj.has(name)) {
                if (component.getType().isPrimitive()) {
                    errors.add("missing required field '" + name + "'");
                } else if (TypeUtils.isOptional(component.getType())) {
                    args[i] = Optional.empty();
                } else {
                    args[i] = null;
                }
                continue;
            }
            try {
                args[i] = bind(obj.opt(name), component.getGenericType());
            } catch (BindingException e) {
                errors.add("field '" + name + "': " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new BindingException("Cannot build " + raw.getSimpleName() + ": " + String.join("; ", errors));
        }
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new BindingException("Failed to construct " + raw.getSimpleName() + ": " + rootMessage(e), e);
        }
    }

    private static Object bindPojo(Class<?> raw, Object json) {
        if (!(json instanceof JSONObject)) {
            throw new BindingException("Expected a JSON object for " + raw.getSimpleName() + " but got: " + describe(json));
        }
        JSONObject obj = (JSONObject) json;
        Object instance;
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new BindingException("Type " + raw.getName() + " needs a no-arg constructor to be used as a structured-output type", e);
        }
        List<String> errors = new ArrayList<>();
        for (Field field : allInstanceFields(raw)) {
            String name = field.getName();
            if (!obj.has(name)) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(instance, bind(obj.opt(name), field.getGenericType()));
            } catch (BindingException e) {
                errors.add("field '" + name + "': " + e.getMessage());
            } catch (IllegalAccessException e) {
                throw new BindingException("Cannot set field '" + name + "' on " + raw.getName(), e);
            }
        }
        if (!errors.isEmpty()) {
            throw new BindingException("Cannot build " + raw.getSimpleName() + ": " + String.join("; ", errors));
        }
        return instance;
    }

    private static Object bindCollection(Class<?> raw, Type targetType, Object json) {
        if (!(json instanceof JSONArray)) {
            throw new BindingException("Expected a JSON array for " + raw.getSimpleName() + " but got: " + describe(json));
        }
        JSONArray arr = (JSONArray) json;
        if (raw.isArray()) {
            Class<?> componentType = raw.getComponentType();
            Object array = Array.newInstance(componentType, arr.size());
            for (int i = 0; i < arr.size(); i++) {
                Array.set(array, i, bind(arr.opt(i), componentType));
            }
            return array;
        }
        Type elementType = TypeUtils.firstTypeArgument(targetType);
        Collection<Object> result = TypeUtils.isSet(raw) ? new LinkedHashSet<Object>() : new ArrayList<Object>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            try {
                result.add(bind(arr.opt(i), elementType));
            } catch (BindingException e) {
                errors.add("index " + i + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new BindingException("Cannot build " + raw.getSimpleName() + ": " + String.join("; ", errors));
        }
        return result;
    }

    private static Object bindMap(Type targetType, Object json) {
        if (!(json instanceof JSONObject)) {
            throw new BindingException("Expected a JSON object for a map but got: " + describe(json));
        }
        JSONObject obj = (JSONObject) json;
        Type valueType = TypeUtils.secondTypeArgument(targetType);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                result.put(key, bind(obj.opt(key), valueType));
            } catch (BindingException e) {
                errors.add("key '" + key + "': " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new BindingException("Cannot build map: " + String.join("; ", errors));
        }
        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object coerceEnum(Class<?> raw, Object json) {
        String name = coerceString(json);
        try {
            return Enum.valueOf((Class<Enum>) raw, name);
        } catch (IllegalArgumentException notExact) {
            try {
                return Enum.valueOf((Class<Enum>) raw, name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException stillNotFound) {
                StringBuilder allowed = new StringBuilder();
                for (Object constant : raw.getEnumConstants()) {
                    if (allowed.length() > 0) {
                        allowed.append(", ");
                    }
                    allowed.append(constant.toString());
                }
                throw new BindingException("'" + name + "' is not a valid value for " + raw.getSimpleName() + "; allowed values: " + allowed);
            }
        }
    }

    private static String coerceString(Object json) {
        if (json instanceof String) {
            return (String) json;
        }
        return String.valueOf(json);
    }

    private static Boolean coerceBoolean(Object json) {
        if (json instanceof Boolean) {
            return (Boolean) json;
        }
        if (json instanceof String) {
            String s = (String) json;
            if (s.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (s.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }
        throw new BindingException("Expected a boolean but got: " + describe(json));
    }

    private static Long coerceLong(Object json) {
        if (json instanceof Number) {
            return ((Number) json).longValue();
        }
        if (json instanceof String) {
            try {
                return (long) Double.parseDouble((String) json);
            } catch (NumberFormatException ignored) {
                // fall through to error below
            }
        }
        throw new BindingException("Expected a number but got: " + describe(json));
    }

    private static Double coerceDouble(Object json) {
        if (json instanceof Number) {
            return ((Number) json).doubleValue();
        }
        if (json instanceof String) {
            try {
                return Double.parseDouble((String) json);
            } catch (NumberFormatException ignored) {
                // fall through to error below
            }
        }
        throw new BindingException("Expected a number but got: " + describe(json));
    }

    /** Converts a JSON tree into plain java.util collections/boxed types, for Object-typed fields. */
    private static Object toPlainJava(Object json) {
        if (json instanceof JSONObject) {
            JSONObject obj = (JSONObject) json;
            Map<String, Object> map = new LinkedHashMap<>();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, toPlainJava(obj.opt(key)));
            }
            return map;
        }
        if (json instanceof JSONArray) {
            JSONArray arr = (JSONArray) json;
            List<Object> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                list.add(toPlainJava(arr.opt(i)));
            }
            return list;
        }
        if (json == JSONObject.NULL) {
            return null;
        }
        return json;
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

    static String describe(Object json) {
        if (json == null || json == JSONObject.NULL) {
            return "null";
        }
        String text = json.toString();
        return truncate(text, 200);
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "... (truncated)";
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
