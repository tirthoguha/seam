package com.tirthoguha.seam.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.tirthoguha.seam.service.BackendProvisioner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Runtime backend administration (BYOK): supply, rotate, or remove a backend's API key without a
 * restart, and inspect which backends are currently configured. Keys are write-only — no response
 * ever echoes one back — and live in memory only (a restart falls back to the env/yml seed).
 *
 * <p>Like the rest of this PoC, these endpoints are unauthenticated and meant for local use; they
 * must gain auth (and tenant scoping) before any multi-user deployment.
 */
@RestController
public class AdminController {

    private final BackendProvisioner provisioner;

    public AdminController(BackendProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /** GET /admin/backends — every declared backend and whether it can currently serve. */
    @GetMapping("/admin/backends")
    public List<BackendProvisioner.BackendStatus> backends() {
        return provisioner.statuses();
    }

    /** PUT /admin/backends/{name}/key  {"apiKey":"sk-..."} — set or rotate, effective immediately. */
    @PutMapping("/admin/backends/{name}/key")
    public BackendProvisioner.BackendStatus setKey(@PathVariable String name,
                                                   @Valid @RequestBody SetKeyRequest request) {
        return provisioner.setKey(name, request.apiKey());
    }

    /** DELETE /admin/backends/{name}/key — remove; the backend stays declared but stops serving. */
    @DeleteMapping("/admin/backends/{name}/key")
    public BackendProvisioner.BackendStatus clearKey(@PathVariable String name) {
        return provisioner.clearKey(name);
    }

    /** Body of the set-key call — the only place a key appears, and it is never echoed back. */
    public record SetKeyRequest(@NotBlank String apiKey) {
    }
}
