package io.github.vinhphan812.mcp.api.config;

/**
 * TLS/SSL configuration for the transport layer.
 */
public final class TlsConfig {
    public final String keystorePath;
    public final String keystorePassword;
    public final String protocol;

    public TlsConfig(String keystorePath, String keystorePassword, String protocol) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.protocol = protocol != null ? protocol : "TLSv1.3";
    }

    public static TlsConfig defaults() {
        return new TlsConfig(null, null, "TLSv1.3");
    }
}
