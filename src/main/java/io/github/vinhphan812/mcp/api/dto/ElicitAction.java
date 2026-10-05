package io.github.vinhphan812.mcp.api.dto;

import java.util.Objects;

/**
 * A single labelled action offered to the client in an elicitation request.
 *
 * <p>Modelled after the MCP 2026-07-28 elicitation schema. The server sends
 * these as {@code requestedSchema.properties.action.enum} when calling
 * {@code elicitation/create}.
 *
 * @since 2026-07-28
 */
public final class ElicitAction {

    private final String label;
    private final String description;

    /**
     * Creates a new action.
     *
     * @param label       short human-readable label (shown in the client UI); must not be blank
     * @param description optional longer description
     */
    public ElicitAction(String label, String description) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        this.label = label.trim();
        this.description = description != null ? description.trim() : null;
    }

    /** Returns the action label. */
    public String getLabel() {
        return label;
    }

    /** Returns the optional description, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** Returns the action label as the canonical identifier. */
    public String getId() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ElicitAction that = (ElicitAction) o;
        return label.equals(that.label);
    }

    @Override
    public int hashCode() {
        return label.hashCode();
    }

    @Override
    public String toString() {
        return "ElicitAction{label='" + label + "', description=" + description + "}";
    }
}
