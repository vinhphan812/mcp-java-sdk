/*
 * Copyright 2025 Phan Thanh Vinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vinhphan812.mcp.api.spi;

import java.util.Map;

/**
 * SPI for tool authorisation. Implement this to enforce scope-based or confirmation-based
 * tool access control.
 *
 * <p>The server calls {@link #denial(String[], boolean, Map)} before invoking a tool.
 * Return {@code null} to allow the call; return a denial message string to reject it.
 *
 * <p>Example: require {@code "admin"} scope for destructive tools.
 *
 * <pre>{@code
 * public class MyAuth implements McpAuthorization {
 *     public String denial(String[] requiredScopes, boolean confirmationRequired,
 *                          Map<String, Object> arguments) {
 *         for (String scope : requiredScopes) {
 *             if (!currentUser.hasScope(scope)) {
 *                 return "Missing required scope: " + scope;
 *             }
 *         }
 *         if (confirmationRequired && !currentUser.confirmed()) {
 *             return "Tool requires user confirmation";
 *         }
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * <p>To enable, set via {@code McpServerConfig.Builder.authorization(myAuth)}.
 * If no authorisation is configured, all tools are allowed by default.
 */
public interface McpAuthorization {

    /** Category constant for read operations (tools with no special scope). */
    String READ = "read";

    /** Category constant for write operations. */
    String WRITE = "write";

    /** Category constant for admin or destructive operations. */
    String ADMIN = "admin";

    /**
     * Check whether the current context is authorised to call a tool.
     *
     * @param requiredScopes         array of required scope names (e.g. "admin", "write", "read")
     *                                — may be empty or null
     * @param confirmationRequired    true if the tool requires explicit user confirmation
     * @param arguments              the tool's input arguments — may be null
     * @return null if authorised; a denial message string if not authorised
     */
    String denial(String[] requiredScopes, boolean confirmationRequired,
                  Map<String, Object> arguments);
}
