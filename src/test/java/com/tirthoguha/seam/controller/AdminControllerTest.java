package com.tirthoguha.seam.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tirthoguha.seam.service.BackendProvisioner;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BackendProvisioner provisioner;

    private static BackendProvisioner.BackendStatus backendStatus(String name, boolean configured) {
        return new BackendProvisioner.BackendStatus(
                name, configured, "https://api.example.com/v1", "some-model", null, "chat");
    }

    @Test
    void listsEveryDeclaredBackendWithConfiguredFlag() throws Exception {
        when(provisioner.statuses()).thenReturn(List.of(backendStatus("docker", true), backendStatus("openai", false)));

        mockMvc.perform(get("/admin/backends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("docker"))
                .andExpect(jsonPath("$[0].configured").value(true))
                .andExpect(jsonPath("$[1].name").value("openai"))
                .andExpect(jsonPath("$[1].configured").value(false));
    }

    @Test
    void putKey_setsKeyAndReturnsStatus_withoutEchoingTheKey() throws Exception {
        when(provisioner.setKey("openai", "sk-secret")).thenReturn(backendStatus("openai", true));

        mockMvc.perform(put("/admin/backends/openai/key")
                        .contentType(APPLICATION_JSON).content("{\"apiKey\":\"sk-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("openai"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(content().string(Matchers.not(Matchers.containsString("sk-secret"))));
    }

    @Test
    void putKey_rejectsBlankKeyWith400() throws Exception {
        mockMvc.perform(put("/admin/backends/openai/key")
                        .contentType(APPLICATION_JSON).content("{\"apiKey\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void deleteKey_clearsKeyAndReportsUnconfigured() throws Exception {
        when(provisioner.clearKey("openai")).thenReturn(backendStatus("openai", false));

        mockMvc.perform(delete("/admin/backends/openai/key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        verify(provisioner).clearKey(eq("openai"));
    }

    @Test
    void putKey_unknownBackendIs400() throws Exception {
        when(provisioner.setKey("nope", "sk-x"))
                .thenThrow(new IllegalArgumentException("Unknown backend 'nope'"));

        mockMvc.perform(put("/admin/backends/nope/key")
                        .contentType(APPLICATION_JSON).content("{\"apiKey\":\"sk-x\"}"))
                .andExpect(status().isBadRequest());
    }
}
