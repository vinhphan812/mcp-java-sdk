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

package io.github.vinhphan812.mcp.api.dto;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * Typed DTO for the MCP 2026-07-28 {@code subscriptions/listen} request parameters.
 *
 * <p>Structure:
 * <pre>{@code
 * {
 *   "notifications": {
 *     "toolsListChanged": boolean,
 *     "promptsListChanged": boolean,
 *     "resourcesListChanged": boolean,
 *     "resourceSubscriptions": ["..."]
 *   }
 * }
 * }</pre>
 *
 *
 * @see io.github.vinhphan812.mcp.core.McpProtocolHandler
 */
public final class SubscriptionListenRequest {

    public static final class Notifications {
        public final boolean toolsListChanged;
        public final boolean promptsListChanged;
        public final boolean resourcesListChanged;
        public final Set<String> resourceSubscriptions;

        public Notifications(boolean toolsListChanged, boolean promptsListChanged, boolean resourcesListChanged, Set<String> resourceSubscriptions) {
            this.toolsListChanged = toolsListChanged;
            this.promptsListChanged = promptsListChanged;
            this.resourcesListChanged = resourcesListChanged;
            this.resourceSubscriptions = Collections.unmodifiableSet(resourceSubscriptions != null ? new HashSet<>(resourceSubscriptions) : Collections.emptySet());
        }
    }

    public final Notifications notifications;

    private SubscriptionListenRequest(Notifications notifications) {
        this.notifications = notifications;
    }

    @SuppressWarnings("unchecked")
    public static SubscriptionListenRequest fromParams(Map<String, Object> params) {
        if (params == null) {
            return EMPTY;
        }

        Object nObj = params.get("notifications");
        if (!(nObj instanceof Map)) {
            return EMPTY;
        }

        Map<String, Object> nMap = (Map<String, Object>) nObj;
        boolean tools = Boolean.TRUE.equals(nMap.get("toolsListChanged"));
        boolean prompts = Boolean.TRUE.equals(nMap.get("promptsListChanged"));
        boolean resources = Boolean.TRUE.equals(nMap.get("resourcesListChanged"));
        
        Set<String> subResources = new HashSet<>();
        Object subResourcesObj = nMap.get("resourceSubscriptions");
        if (subResourcesObj instanceof java.util.Collection) {
            for (Object item : (java.util.Collection<?>) subResourcesObj) {
                if (item instanceof String) {
                    subResources.add((String) item);
                }
            }
        }

        return new SubscriptionListenRequest(new Notifications(tools, prompts, resources, subResources));
    }

    public static final SubscriptionListenRequest EMPTY = new SubscriptionListenRequest(
            new Notifications(false, false, false, Collections.emptySet())
    );
}
