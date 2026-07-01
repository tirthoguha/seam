package com.tirthoguha.seam.provider.openai;

import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.models.models.Model;
import com.tirthoguha.seam.provider.ChatProviderException;

/**
 * Shared helper for listing a backend's models via the SDK, so both {@link OpenAiChatProvider} and
 * {@link OpenAiResponsesProvider} expose the backend's full catalog without duplicating SDK code.
 * Package-private: like the providers, it's allowed to import {@code com.openai.*}.
 */
final class OpenAiModels {

    private OpenAiModels() {
    }

    /** The backend's own model ids (verbatim — backends accept their own reported ids for routing). */
    static List<String> list(String backend, OpenAIClient client) {
        try {
            return client.models().list().data().stream()
                    .map(Model::id)
                    .toList();
        } catch (OpenAIException e) {
            throw new ChatProviderException(backend, "OpenAI model listing failed", e);
        }
    }
}
