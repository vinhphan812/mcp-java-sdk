package io.github.vinhphan812.mcp.api.config;

/**
 * TLS/SSL configuration for the transport layer.
 *
 * @deprecated This class does not configure TLS. TLS termination is handled by a
 * reverse proxy, load balancer, or ingress controller. No replacement SDK TLS API
 * is planned. Removal is reserved for the next explicitly planned major release.
 * See {@code docs/adr/ADR-0014-tls-transport-contract.md}.
 */
@Deprecated
public final class TlsConfig {
    public final String keystorePath;
    public final String keystorePassword;
    public final String protocol;

    /**
     * Creates a TlsConfig with the given parameters.
     *
     * @deprecated This constructor does not configure TLS. TLS termination is
     * handled by a reverse proxy, load balancer, or ingress controller.
     * Removal is reserved for the next explicitly planned major release.
     */
    @Deprecated
    public TlsConfig(String keystorePath, String keystorePassword, String protocol) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.protocol = protocol != null ? protocol : "TLSv1.3";
    }

    /**
     * Returns a TlsConfig with default values.
     *
     * @deprecated This factory does not configure TLS. TLS termination is
     * handled by a reverse proxy, load balancer, or ingress controller.
     * Removal is reserved for the next explicitly planned major release.
     */
    @Deprecated
    public static TlsConfig defaults() {
        return new TlsConfig(null, null, "TLSv1.3");
    }
}
