package com.tirthoguha.omnillm.dto.openai;

import java.util.List;

/**
 * OpenAI {@code GET /v1/models} response. Clients (e.g. Open WebUI) call this to populate their model
 * picker; the gateway aggregates every model each backend offers (its own {@code /v1/models}) and
 * lists them as {@code <backend>:<id>} — falling back to a backend's configured defaults if it can't
 * be listed.
 */
public record OpenAiModelList(String object, List<Model> data) {

    public static OpenAiModelList of(List<Model> data) {
        return new OpenAiModelList("list", data);
    }

    public record Model(String id, String object, long created, String owned_by) {
        public static Model of(String id, long created, String ownedBy) {
            return new Model(id, "model", created, ownedBy);
        }
    }
}
