package com.tirthoguha.seam.provider;

/**
 * A request named a backend that is <em>declared</em> in {@code app.llm.backends} but currently has
 * no API key, so no provider is provisioned for it. Distinct from the {@link IllegalArgumentException}
 * thrown for a backend that isn't declared at all: unknown → the caller's mistake (400); declared but
 * keyless → server-side configuration state (503) fixable via env or
 * {@code PUT /admin/backends/{name}/key} without a restart.
 */
public class BackendNotConfiguredException extends RuntimeException {

    private final String backend;

    public BackendNotConfiguredException(String backend) {
        super("Backend '" + backend + "' is declared but has no API key configured. "
                + "Supply one via PUT /admin/backends/" + backend + "/key or the backend's key env var.");
        this.backend = backend;
    }

    /** Name of the declared-but-unconfigured backend. */
    public String backend() {
        return backend;
    }
}
