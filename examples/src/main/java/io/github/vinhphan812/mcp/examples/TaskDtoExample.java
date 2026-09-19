package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpTool;
import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.Tools;

@Tools
public final class TaskDtoExample {

    public static class TaskDto {
        private String id;
        private String title;
        private boolean completed;

        public TaskDto() {}
        public TaskDto(String id, String title, boolean completed) {
            this.id = id;
            this.title = title;
            this.completed = completed;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
    }

    @McpTool(name = "create-task", description = "Create a new task")
    public String createTask(@McpParam(name = "task", description = "Task object") TaskDto task) {
        return "Created task: " + task.getTitle() + " (ID: " + task.getId() + ")";
    }
}
