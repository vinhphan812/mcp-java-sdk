package io.github.vinhphan812.mcp.transport;

/**
 * Transport mode selection for HTTP handling.
 */
public enum TransportMode {
	/**
	 * Legacy HTTP+SSE mode (deprecated).
	 * Uses POST + GET + DELETE dual endpoints.
	 */
	HTTP_SSE,
	/**
	 * Modern Streamable HTTP mode.
	 * Uses single POST endpoint with server-driven streaming.
	 */
	STREAMABLE_HTTP,
	/**
	 * Auto-detect mode.
	 * Automatically selects between HTTP_SSE and STREAMABLE_HTTP
	 * based on client request characteristics.
	 */
	AUTO
}
