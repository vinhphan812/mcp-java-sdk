package io.github.vinhphan812.mcp.api.config;

/** Policy for a destructive tool. */
public final class DestructiveToolPolicy {
    /** Maximum lifetime calls allowed for this tool in a session. */
    public final int cap;
    /** Cool-down period in milliseconds after a call before the next is allowed. */
    public final long cooldownMs;
    /** Abuse score weight added to the session when this tool's cap is exceeded. Defaults to 3. */
    public final int abuseWeight;

    /**
     * Constructs a policy using the default abuse weight of 3.
     *
     * @param cap        maximum lifetime calls allowed in a session
     * @param cooldownMs cool-down period in milliseconds between calls
     */
    public DestructiveToolPolicy(int cap, long cooldownMs) {
        this(cap, cooldownMs, 3);
    }

    /**
     * Constructs a policy with explicit abuse weight.
     *
     * @param cap         maximum lifetime calls allowed in a session
     * @param cooldownMs  cool-down period in milliseconds between calls
     * @param abuseWeight abuse score weight added when the cap is exceeded
     */
    public DestructiveToolPolicy(int cap, long cooldownMs, int abuseWeight) {
        if (cap <= 0) throw new IllegalArgumentException("cap must be positive");
        if (cooldownMs < 0) throw new IllegalArgumentException("cooldownMs must not be negative");
        if (abuseWeight < 0) throw new IllegalArgumentException("abuseWeight must not be negative");
        this.cap = cap;
        this.cooldownMs = cooldownMs;
        this.abuseWeight = abuseWeight;
    }
}
