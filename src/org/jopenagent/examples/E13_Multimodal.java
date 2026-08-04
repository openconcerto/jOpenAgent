package org.jopenagent.examples;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.llm.media.Image;

/**
 * Mirrors nooa/examples/quickstart/13_multimodal.py: an {@link Image}-typed parameter is attached
 * to the LLM request as real multimodal content (not base64-dumped into the text prompt, see
 * {@link org.jopenagent.core.PromptBuilder#collectMedia}). Generates a small PNG on the fly with
 * {@code java.awt}/{@code ImageIO} (both JDK-builtin) so this example needs no external image file.
 */
@SystemPrompt("You describe the contents of images concisely.")
public class E13_Multimodal extends Agent {

    public E13_Multimodal(AgentConfig config) {
        super(config);
    }

    /**
     * The multimodal capability: the {@link Image}-typed parameter is attached to the request as
     * real image content by {@link org.jopenagent.core.PromptBuilder#collectMedia}, not serialized into
     * the text prompt.
     */
    @Generative("Describe what shape and color is shown in the image, in one short sentence.")
    public String describeImage(Image image) {
        return generate(image);
    }

    public static void main(String[] args) throws IOException {
        Image image = Image.fromBytes(renderRedCirclePng(), "image/png");

        E13_Multimodal agent = new E13_Multimodal(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println(agent.describeImage(image));
    }

    private static byte[] renderRedCirclePng() throws IOException {
        BufferedImage canvas = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 200, 200);
        graphics.setColor(Color.RED);
        graphics.fillOval(40, 40, 120, 120);
        graphics.dispose();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", buffer);
        return buffer.toByteArray();
    }
}
