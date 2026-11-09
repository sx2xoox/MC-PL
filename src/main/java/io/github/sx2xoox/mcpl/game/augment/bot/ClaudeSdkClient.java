package io.github.sx2xoox.mcpl.game.augment.bot;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;

/**
 * {@link ClaudeClient} on the official Anthropic Java SDK ({@code com.anthropic:anthropic-java}, loaded by the server
 * from plugin.yml {@code libraries:}). No retries (a late answer is a useless answer: the brain plays on), no sampling
 * parameters and no thinking parameter (the model's default thinking; {@code output_config.effort} sets its depth),
 * structured output by JSON schema, the system prompt behind a cache breakpoint.
 */
final class ClaudeSdkClient implements ClaudeClient {

    private final AnthropicClient client;

    ClaudeSdkClient(String apiKey, String baseUrl, long timeoutMillis) {
        // the key goes in the classic x-api-key header (the SDK would otherwise send it as a bearer token)
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .putHeader("x-api-key", apiKey)
                .timeout(Duration.ofMillis(timeoutMillis))
                .maxRetries(0);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
    }

    /** The request as the SDK builds it (also for tests). */
    static MessageCreateParams params(Request request) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> e : request.schema().entrySet()) {
            schema.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(request.user())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(request.effort()))
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build())
                .build();
    }

    @Override
    public Reply call(Request request) throws Failure {
        Message message;
        try {
            message = client.messages().create(params(request));
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            FailureKind kind = status == 429 ? FailureKind.RATE_LIMIT : status == 529 ? FailureKind.OVERLOADED
                    : status == 401 || status == 403 ? FailureKind.AUTH : FailureKind.HTTP;
            throw new Failure(kind, status, "HTTP " + status);
        } catch (AnthropicIoException e) {
            throw new Failure(timedOut(e) ? FailureKind.TIMEOUT : FailureKind.IO, 0, causes(e));
        } catch (RuntimeException e) {
            throw new Failure(timedOut(e) ? FailureKind.TIMEOUT : FailureKind.OTHER, 0, causes(e));
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        Usage usage = message.usage();
        return new Reply(message.stopReason().map(r -> r.asString()).orElse(""), text.toString(),
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
    }

    /** The exception classes down the cause chain (no messages: nothing of the request in the log). */
    private static String causes(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e; t != null && b.length() < 200; t = t.getCause()) {
            b.append(b.isEmpty() ? "" : " <- ").append(t.getClass().getSimpleName());
        }
        return b.toString();
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true; // SocketTimeoutException, OkHttp's call timeout
            }
        }
        return false;
    }

    @Override
    public void close() {
        client.close();
    }
}
