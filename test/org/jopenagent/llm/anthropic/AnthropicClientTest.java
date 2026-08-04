package org.jopenagent.llm.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.media.Image;

/** Verifies wire-format JSON construction without any network call, by exercising the package-private request builder directly. */
class AnthropicClientTest {

    private AnthropicClient newClient() {
        return new AnthropicClient("test-key", "claude-test", "http://unused.invalid", HttpClient.newHttpClient());
    }

    @Test
    void systemPromptGoesToTopLevelField() {
        LlmRequest request = LlmRequest.builder().systemPrompt("be nice").addMessage(LlmMessage.user("hi")).build();
        JSONObject body = newClient().buildRequestBody(request);
        assertEquals("be nice", body.getString("system"));
    }

    @Test
    void consecutiveToolResultsAreMergedIntoOneUserTurn() {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.toolResult("call-1", "result-1"));
        messages.add(LlmMessage.toolResult("call-2", "result-2"));
        LlmRequest request = LlmRequest.builder().systemPrompt("s").addMessages(messages).build();

        JSONObject body = newClient().buildRequestBody(request);
        JSONArray anthropicMessages = body.getJSONArray("messages");

        assertEquals(1, anthropicMessages.size());
        JSONObject turn = anthropicMessages.getJSONObject(0);
        assertEquals("user", turn.getString("role"));
        JSONArray blocks = turn.getJSONArray("content");
        assertEquals(2, blocks.size());
        assertEquals("tool_result", blocks.getJSONObject(0).getString("type"));
        assertEquals("call-1", blocks.getJSONObject(0).getString("tool_use_id"));
        assertEquals("call-2", blocks.getJSONObject(1).getString("tool_use_id"));
    }

    @Test
    void imageAttachmentBecomesBase64ImageBlock() {
        Image image = Image.fromBytes(new byte[] { 1, 2, 3 }, "image/png");
        LlmMessage message = LlmMessage.userWithMedia("what is this?", List.of(image));
        LlmRequest request = LlmRequest.builder().systemPrompt("s").addMessage(message).build();

        JSONObject body = newClient().buildRequestBody(request);
        JSONArray blocks = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content");

        assertEquals("image", blocks.getJSONObject(0).getString("type"));
        JSONObject source = blocks.getJSONObject(0).getJSONObject("source");
        assertEquals("base64", source.getString("type"));
        assertEquals("image/png", source.getString("media_type"));
        assertTrue(blocks.getJSONObject(1).getString("text").contains("what is this?"));
    }
}
