package io.github.vinhphan812.mcp.api.spi;

/**
 * SPI for managing API keys used for request authentication/authorization.
 *
 * Implementations should be thread-safe for concurrent access to {@link #isValid(String)},
 * {@link #rotateKey()}, and {@link #setKey(String)} methods.
 */
public interface ApiKeyStore {
    /**
     * Returns the currently active API key. 
     * @return the currently active API key
     */
    String getActiveKey();

    /** Rotates to the next API key. */
    void rotateKey();

    /**
     * Sets a specific API key. 
     * @param key the API key to set
     */
    void setKey(String key);

    /**
     * Validates an API key. 
     * @param key the API key to validate
     * @return true if valid, false otherwise
     */
    boolean isValid(String key);
}
