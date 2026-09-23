package io.github.vinhphan812.mcp.api.security;

import io.github.vinhphan812.mcp.api.spi.ApiKeyStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default implementation of {@link ApiKeyStore}.
 * Supports volatile in-memory storage and optional file-backed persistence.
 */
public class DefaultApiKeyStore implements ApiKeyStore {
    private final AtomicReference<String> activeKey = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Path storagePath;
    private final ScheduledExecutorService scheduler;

    /**
     * Initializes a new DefaultApiKeyStore.
     *
     * @param initialKey the secret used to sign/validate rotating keys
     * @param filePath path to the file-backed key store (may be {@code null} for in-memory-only)
     * @param rotateIntervalSeconds auto-rotation interval in seconds (0 or negative disables rotation)
     */
    public DefaultApiKeyStore(String initialKey, String filePath, long rotateIntervalSeconds) {
        activeKey.set(initialKey);
        this.storagePath = filePath != null ? Paths.get(filePath) : null;
        if (storagePath != null) {
            loadFromDisk();
        }

        if (rotateIntervalSeconds > 0) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "apikey-rotator");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleAtFixedRate(this::rotateKey, rotateIntervalSeconds, rotateIntervalSeconds, TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    private void loadFromDisk() {
        if (storagePath != null && Files.exists(storagePath)) {
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(storagePath)) {
                props.load(is);
                String key = props.getProperty("activeKey");
                if (key != null) activeKey.set(key);
            } catch (IOException e) {
                // Log and ignore or throw
            }
        }
    }

    private void saveToDisk() {
        if (storagePath != null) {
            Properties props = new Properties();
            props.setProperty("activeKey", activeKey.get());
            try (OutputStream os = Files.newOutputStream(storagePath)) {
                props.store(os, "API Key Store");
            } catch (IOException e) {
                // Log and ignore or throw
            }
        }
    }

    @Override
    public String getActiveKey() {
        return activeKey.get();
    }

    @Override
    public void rotateKey() {
        lock.lock();
        try {
            String newKey = UUID.randomUUID().toString();
            activeKey.set(newKey);
            saveToDisk();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setKey(String key) {
        lock.lock();
        try {
            activeKey.set(key);
            saveToDisk();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isValid(String key) {
        return key != null && key.equals(activeKey.get());
    }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
    }
}
