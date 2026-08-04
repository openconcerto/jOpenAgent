package org.jopenagent.llm.media;

/** A video attachment for a multimodal {@code LlmMessage}. Provider support for inline video is uneven; treat this as best-effort. */
public final class Video extends Media {

    private Video(String dataUrl, String mediaType) {
        super(dataUrl, mediaType, null);
    }

    /** References a remote video by URL rather than inlining its bytes. */
    public static Video fromUrl(String url, String mediaType) {
        return new Video(url, mediaType);
    }

    /** Wraps raw video bytes as an inline base64 data URL. */
    public static Video fromBytes(byte[] data, String mediaType) {
        return new Video(toDataUrl(data, mediaType), mediaType);
    }
}
