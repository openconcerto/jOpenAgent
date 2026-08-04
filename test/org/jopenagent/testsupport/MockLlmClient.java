package org.jopenagent.testsupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.llm.ToolCall;

/** A scripted {@link LlmClient} for tests: enqueue canned responses, then assert on what was sent. */
public final class MockLlmClient implements LlmClient {

    private final Deque<LlmResponse> queue = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private final String model;

    public MockLlmClient() {
        this("mock-model");
    }

    public MockLlmClient(String model) {
        this.model = model;
    }

    public MockLlmClient enqueue(LlmResponse response) {
        queue.add(response);
        return this;
    }

    public MockLlmClient enqueueText(String content) {
        return enqueue(new LlmResponse(content, Collections.<ToolCall>emptyList(), "end_turn", 0, 0));
    }

    public MockLlmClient enqueueToolCall(ToolCall call) {
        List<ToolCall> calls = new ArrayList<>();
        calls.add(call);
        return enqueue(new LlmResponse("", calls, "tool_use", 0, 0));
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        requests.add(request);
        if (queue.isEmpty()) {
            throw new IllegalStateException("MockLlmClient: no more queued responses (this was call #" + requests.size() + ")");
        }
        return queue.poll();
    }

    @Override
    public String modelName() {
        return model;
    }

    public List<LlmRequest> getRequests() {
        return requests;
    }

    public LlmRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public int callCount() {
        return requests.size();
    }
}
