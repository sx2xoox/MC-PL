package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The request the SDK sends (against a local stub, never the real API) and how answers and errors come back. */
class ClaudeSdkClientTest {

    private ClaudeStub stub;
    private ClaudeSdkClient client;

    @BeforeEach
    void setUp() throws IOException {
        stub = new ClaudeStub();
        client = new ClaudeSdkClient("test-key", stub.url(), 2_000);
    }

    @AfterEach
    void tearDown() {
        client.close();
        stub.close();
    }

    private static ClaudeClient.Request request() {
        return new ClaudeClient.Request("claude-opus-5-5", "low", 2048,
                ClaudePrompt.system(List.of(new ClaudePrompt.AugmentLine("iron_body", "강철 체력", "silver", "체력 +4"))),
                ClaudePrompt.user(ClaudeDecisionTest.snapshot(), "", null, ""), ClaudePrompt.schema());
    }

    @Test
    void theRequestHasTheModelEffortSchemaCacheBreakpointAndNothingElse() throws Exception {
        stub.responder = seen -> ClaudeStub.ok(ClaudeStub.decision("ATTACK", "영희 봇", "BOW", "", "n", ""));
        ClaudeClient.Reply reply = client.call(request());
        assertEquals(1, stub.requests.size());
        ClaudeStub.Seen seen = stub.requests.getFirst();
        assertEquals("test-key", seen.header("x-api-key"), seen.headers().toString());
        assertEquals(null, seen.header("authorization"), "the key once, in x-api-key");
        assertTrue(seen.header("anthropic-version") != null && !seen.header("anthropic-version").isBlank());
        JsonObject body = seen.body();
        assertEquals("claude-opus-5-5", body.get("model").getAsString());
        assertEquals(2048, body.get("max_tokens").getAsInt());
        JsonObject output = body.getAsJsonObject("output_config");
        assertEquals("low", output.get("effort").getAsString());
        JsonObject format = output.getAsJsonObject("format");
        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("schema");
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertTrue(schema.getAsJsonObject("properties").has("goal"));
        JsonObject system = body.getAsJsonArray("system").get(0).getAsJsonObject();
        assertEquals("ephemeral", system.getAsJsonObject("cache_control").get("type").getAsString());
        assertTrue(system.get("text").getAsString().contains("iron_body"));
        // no sampling parameters, no thinking parameter, no prefill: one user message last
        for (String banned : List.of("temperature", "top_p", "top_k", "thinking", "stream")) {
            assertFalse(body.has(banned), banned);
        }
        var messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(seen.user().contains("KNOWN ENEMIES"));
        // the answer
        assertEquals("end_turn", reply.stopReason());
        assertEquals(300, reply.inputTokens());
        assertEquals(120, reply.outputTokens());
        assertEquals(2000, reply.cacheReadTokens());
        assertEquals(ClaudeOrders.Goal.ATTACK, ClaudeDecision.parse(reply.text()).goal());
    }

    @Test
    void aRefusalComesBackAsItsStopReason() throws Exception {
        stub.responder = seen -> ClaudeStub.message("refusal", "", 0);
        assertEquals("refusal", client.call(request()).stopReason());
    }

    @Test
    void errorsAreSortedAndNeverRetried() {
        stub.responder = seen -> ClaudeStub.error(429, "rate_limit_error");
        assertEquals(ClaudeClient.FailureKind.RATE_LIMIT,
                assertThrows(ClaudeClient.Failure.class, () -> client.call(request())).kind());
        assertEquals(1, stub.requests.size(), "no retries");
        stub.responder = seen -> ClaudeStub.error(529, "overloaded_error");
        assertEquals(ClaudeClient.FailureKind.OVERLOADED,
                assertThrows(ClaudeClient.Failure.class, () -> client.call(request())).kind());
        stub.responder = seen -> ClaudeStub.error(401, "authentication_error");
        ClaudeClient.Failure auth = assertThrows(ClaudeClient.Failure.class, () -> client.call(request()));
        assertEquals(ClaudeClient.FailureKind.AUTH, auth.kind());
        assertFalse(auth.getMessage().contains("test-key"));
        stub.responder = seen -> ClaudeStub.error(500, "api_error");
        assertEquals(ClaudeClient.FailureKind.HTTP,
                assertThrows(ClaudeClient.Failure.class, () -> client.call(request())).kind());
    }

    @Test
    void aSlowAnswerTimesOut() {
        stub.responder = seen -> ClaudeStub.message("end_turn", "{}", 4_000);
        assertEquals(ClaudeClient.FailureKind.TIMEOUT,
                assertThrows(ClaudeClient.Failure.class, () -> client.call(request())).kind());
    }
}
