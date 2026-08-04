package org.jopenagent.trace.viewer.swing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

/**
 * Read-only adapter over the plain span-tree JSON shape produced by
 * {@link org.jopenagent.trace.JsonTraceExporter#toJson(org.jopenagent.trace.Span)}, used as the single node
 * type for {@link TraceViewerSwingApp}'s tree, regardless of whether the JSON came from an
 * in-process {@link org.jopenagent.trace.Span} (converted locally) or was fetched over HTTP from a
 * remote {@link org.jopenagent.trace.viewer.TraceViewerServer} (same wire shape either way).
 */
final class JsonTraceNode {

    private final JSONObject json;

    JsonTraceNode(JSONObject json) {
        this.json = json;
    }

    String getId() {
        return json.optString("id", "");
    }

    String getKind() {
        return json.optString("kind", "");
    }

    String getName() {
        return json.optString("name", "");
    }

    double getDurationMillis() {
        return json.optDouble("durationMillis", -1);
    }

    String getStatus() {
        return json.optString("status", "OK");
    }

    boolean isError() {
        return "ERROR".equals(getStatus());
    }

    String getErrorMessage() {
        return json.has("error") ? json.optString("error", "") : null;
    }

    Map<String, String> getAttributes() {
        Map<String, String> result = new LinkedHashMap<>();
        JSONObject attributes = json.optJSONObject("attributes");
        if (attributes == null) {
            return result;
        }
        Iterator<String> keys = attributes.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, String.valueOf(attributes.opt(key)));
        }
        return result;
    }

    List<JsonTraceNode> getChildren() {
        List<JsonTraceNode> result = new ArrayList<>();
        JSONArray children = json.optJSONArray("children");
        if (children == null) {
            return result;
        }
        for (int i = 0; i < children.size(); i++) {
            result.add(new JsonTraceNode(children.getJSONObject(i)));
        }
        return result;
    }

    /** One-line {@code [kind] name (duration)} summary, with a trailing warning glyph on error, used as this node's rendered text in the Swing tree ({@link #toString()}, {@link TraceViewerSwingApp}'s cell renderer). */
    String label() {
        String duration = getDurationMillis() >= 0 ? String.format("%.1fms", getDurationMillis()) : "?";
        // The warning sign is written as a unicode escape rather than a raw character: javac
        // resolves such escapes before any platform-encoding-dependent tokenization, so this
        // renders correctly regardless of the source file's actual encoding vs. -encoding flag
        // (a raw UTF-8 character here previously mojibake'd under Windows' default ANSI encoding).
        return "[" + getKind() + "] " + getName() + " (" + duration + ")" + (isError() ? " \u26A0" : "");
    }

    @Override
    public String toString() {
        return label();
    }
}
