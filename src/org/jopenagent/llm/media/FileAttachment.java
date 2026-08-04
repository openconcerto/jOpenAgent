package org.jopenagent.llm.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A generic file/document attachment (e.g. a PDF) for a multimodal {@code LlmMessage}. */
public final class FileAttachment extends Media {

    private final String filename;

    private FileAttachment(String dataUrl, String mediaType, String filename) {
        super(dataUrl, mediaType, null);
        this.filename = filename;
    }

    /** Wraps raw file bytes as an inline base64 data URL, tagged with the original {@code filename} for providers that require one. */
    public static FileAttachment fromBytes(byte[] data, String mediaType, String filename) {
        return new FileAttachment(toDataUrl(data, mediaType), mediaType, filename);
    }

    /** Reads a file from disk and wraps it as an inline base64 data URL; media type defaults to {@code application/pdf} for {@code .pdf} files and {@code application/octet-stream} otherwise. */
    public static FileAttachment fromFile(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            String name = path.getFileName().toString();
            String mediaType = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
            return fromBytes(data, mediaType, name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read file: " + path, e);
        }
    }

    public String getFilename() {
        return filename;
    }
}
