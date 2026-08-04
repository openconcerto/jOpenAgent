package org.jopenagent.llm.media;

import java.util.Collections;
import java.util.Map;

/**
 * Base type for multimodal message content: jOpenAgent's equivalent of NOOA's {@code Media} base
 * class (see {@code nooa/src/nooa/media.py}). Concrete subclasses ({@link Image}, {@link Audio},
 * {@link Video}, {@link FileAttachment}) hold either a {@code data:<mime>;base64,...} URL or a
 * plain remote URL, plus provider-specific hints ({@code vendorMetadata}, e.g. Anthropic/OpenAI
 * detail levels).
 */
public abstract class Media {

    private final String dataUrl;
    private final String mediaType;
    private final Map<String, Object> vendorMetadata;

    protected Media(String dataUrl, String mediaType, Map<String, Object> vendorMetadata) {
        this.dataUrl = dataUrl;
        this.mediaType = mediaType;
        this.vendorMetadata = vendorMetadata != null ? vendorMetadata : Collections.<String, Object>emptyMap();
    }

    public String getDataUrl() {
        return dataUrl;
    }

    public String getMediaType() {
        return mediaType;
    }

    public Map<String, Object> getVendorMetadata() {
        return vendorMetadata;
    }

    public boolean isRemoteUrl() {
        return !dataUrl.startsWith("data:");
    }

    /** The raw base64 payload if this is a data: URL, or null if it's a plain remote URL. */
    public String base64Data() {
        if (isRemoteUrl()) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        return comma >= 0 ? dataUrl.substring(comma + 1) : "";
    }

    /** Builds a {@code data:<mediaType>;base64,...} URL from raw bytes; shared by every concrete subclass's {@code fromBytes}/{@code fromFile} factories. */
    protected static String toDataUrl(byte[] data, String mediaType) {
        return "data:" + mediaType + ";base64," + java.util.Base64.getEncoder().encodeToString(data);
    }
}
