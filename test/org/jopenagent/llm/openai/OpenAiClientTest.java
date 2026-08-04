package org.jopenagent.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.llm.media.Image;

/** Verifies wire-format JSON construction without any network call, by exercising the package-private request builder directly. */
class OpenAiClientTest {

    private OpenAiClient newClient() {
        return new OpenAiClient(null, "gpt-test", "http://unused.invalid", java.net.http.HttpClient.newHttpClient());
    }

    @Test
    void systemPromptBecomesFirstMessage() {
        LlmRequest request = LlmRequest.builder().systemPrompt("be nice").addMessage(LlmMessage.user("hi")).build();
        JSONObject body = newClient().buildRequestBody(request);
        JSONArray messages = body.getJSONArray("messages");
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("be nice", messages.getJSONObject(0).getString("content"));
    }

    @Test
    void assistantToolCallArgumentsAreJsonEncodedStrings() {
        JSONObject args = new JSONObject();
        args.put("item", "widget");
        ToolCall call = new ToolCall("call-1", "getStock", args);
        LlmRequest request = LlmRequest.builder().systemPrompt("s").addMessage(LlmMessage.assistantToolCalls("", List.of(call))).build();

        JSONObject body = newClient().buildRequestBody(request);
        JSONObject assistantMessage = body.getJSONArray("messages").getJSONObject(1);
        JSONObject toolCallJson = assistantMessage.getJSONArray("tool_calls").getJSONObject(0);
        String argumentsText = toolCallJson.getJSONObject("function").getString("arguments");

        assertEquals("{\"item\":\"widget\"}", argumentsText);
    }

    @Test
    void imageAttachmentBecomesImageUrlBlock() {
        Image image = Image.fromBytes(new byte[] { 1, 2, 3 }, "image/png");
        LlmMessage message = LlmMessage.userWithMedia("describe this", List.of(image));
        LlmRequest request = LlmRequest.builder().systemPrompt("s").addMessage(message).build();

        JSONObject body = newClient().buildRequestBody(request);
        JSONArray blocks = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content");

        assertEquals("text", blocks.getJSONObject(0).getString("type"));
        assertEquals("image_url", blocks.getJSONObject(1).getString("type"));
        assertEquals(image.getDataUrl(), blocks.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }
}
