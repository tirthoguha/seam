package com.tirthoguha.omnillm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/**
 * Live connectivity demo / integration check across both backends at once — proving the same
 * OpenAI Java SDK talks to offline (Docker Model Runner) and online (OpenAI cloud) just by
 * swapping base-url + key + model. This is the hands-on confirmation of "both available";
 * choosing one per operation in the app itself is a separate (future) change.
 *
 * <p>Named {@code *IT} so it is NOT picked up by the normal {@code mvn test} unit run (which stays
 * offline and fast). Run it explicitly:
 *
 * <pre>{@code
 *   # all backends that are reachable / configured (others self-skip):
 *   mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false
 *
 *   # to include OpenAI cloud, provide a key:
 *   OPENAI_API_KEY=sk-... mvn test -Dtest=BackendConnectivityIT -DfailIfNoTests=false
 * }</pre>
 *
 * <p>Each backend is its own test and {@link Assumptions skips itself} when unavailable, so the
 * class never fails just because you happen to be offline or keyless. Endpoints/models can be
 * overridden via env vars (same names the app uses).
 */
@DisplayName("Backend connectivity (offline + online)")
class BackendConnectivityIT {

    private static final String PROMPT = "Reply with the single word: pong";

    @Test
    @DisplayName("offline · Docker Model Runner")
    void dockerModelRunnerResponds() {
        String baseUrl = env("DMR_BASE_URL", "http://localhost:12434/engines/v1");
        assumeReachable("Docker Model Runner", baseUrl);
        confirmResponse("docker", baseUrl, "docker", env("DMR_MODEL", "ai/gemma3"));
    }

    @Test
    @DisplayName("online · OpenAI cloud")
    void openAiCloudResponds() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "OPENAI_API_KEY not set — skipping online (OpenAI cloud) check");
        confirmResponse("openai", "https://api.openai.com/v1", apiKey, env("OPENAI_MODEL", "gpt-4o-mini"));
    }

    // --- shared logic -------------------------------------------------------

    /** Builds a client for one backend, sends the prompt, prints and asserts a non-blank reply. */
    private void confirmResponse(String name, String baseUrl, String apiKey, String model) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(PROMPT)
                .build();

        ChatCompletion completion = client.chat().completions().create(params);

        String reply = completion.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElse("");

        System.out.printf("✅ [%-6s] %-13s @ %s%n      → %s%n",
                name, model, baseUrl, reply.strip());

        assertThat(reply)
                .as("%s (%s) should return a non-blank reply", name, baseUrl)
                .isNotBlank();
    }

    /** Skip (don't fail) the test when the local endpoint isn't listening. */
    private static void assumeReachable(String name, String baseUrl) {
        boolean up = canConnect(baseUrl + "/models");
        if (!up) {
            System.out.printf("⏭  %s not reachable at %s — skipping%n", name, baseUrl);
        }
        Assumptions.assumeTrue(up, name + " not reachable at " + baseUrl);
    }

    /** Any HTTP response (even 401/404) means something is listening; only a connect failure is "down". */
    private static boolean canConnect(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
