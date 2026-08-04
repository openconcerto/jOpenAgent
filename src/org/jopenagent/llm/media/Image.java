package org.jopenagent.llm.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** An image attachment for a multimodal {@code LlmMessage}. */
public final class Image extends Media {

    private Image(String dataUrl, String mediaType, Map<String, Object> vendorMetadata) {
        super(dataUrl, mediaType, vendorMetadata);
    }

    /** Wraps raw image bytes (e.g. {@code "image/png"}) as an inline base64 data URL. */
    public static Image fromBytes(byte[] data, String mediaType) {
        return new Image(toDataUrl(data, mediaType), mediaType, null);
    }

    /** Same as {@link #fromBytes(byte[], String)}, plus provider-specific hints (e.g. Anthropic/OpenAI detail level) carried in {@code vendorMetadata}. */
    public static Image fromBytes(byte[] data, String mediaType, Map<String, Object> vendorMetadata) {
        return new Image(toDataUrl(data, mediaType), mediaType, vendorMetadata);
    }

    /** Reads an image file from disk and wraps it as an inline base64 data URL, guessing the media type from the file extension. */
    public static Image fromFile(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            return fromBytes(data, guessMediaType(path.getFileName().toString()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read image file: " + path, e);
        }
    }

    /** References a remote image by URL rather than inlining its bytes; the media type is guessed from the URL's extension. */
    public static Image fromUrl(String url) {
        return new Image(url, guessMediaType(url), null);
    }

    private static String guessMediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }
}
