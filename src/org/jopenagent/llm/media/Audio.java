package org.jopenagent.llm.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** An audio attachment for a multimodal {@code LlmMessage} (e.g. OpenAI's {@code input_audio}). */
public final class Audio extends Media {

    private Audio(String dataUrl, String mediaType) {
        super(dataUrl, mediaType, null);
    }

    /** Wraps raw audio bytes (e.g. {@code "audio/wav"}) as an inline base64 data URL. */
    public static Audio fromBytes(byte[] data, String mediaType) {
        return new Audio(toDataUrl(data, mediaType), mediaType);
    }

    /** Reads an audio file from disk and wraps it as an inline base64 data URL, guessing the media type from the file extension. */
    public static Audio fromFile(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            return fromBytes(data, guessMediaType(path.getFileName().toString()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read audio file: " + path, e);
        }
    }

    /** The short format token some providers expect (e.g. OpenAI's input_audio.format: "wav"/"mp3"). */
    public String formatToken() {
        String type = getMediaType();
        int slash = type.indexOf('/');
        return slash >= 0 ? type.substring(slash + 1) : type;
    }

    private static String guessMediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) {
            return "audio/mp3";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        return "audio/wav";
    }
}
