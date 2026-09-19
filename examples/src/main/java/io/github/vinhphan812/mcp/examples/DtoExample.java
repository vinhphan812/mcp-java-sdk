package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpTool;
import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.Tools;
import java.util.Map;
import java.util.LinkedHashMap;

@Tools
public final class DtoExample {

    public static class UserDto {
        private String name;
        private int age;

        public UserDto() {}
        public UserDto(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    @McpTool(name = "process-user", description = "Process a user DTO")
    public String processUser(@McpParam(name = "user", description = "User object") UserDto user) {
        return "Processed user: " + user.getName() + ", " + user.getAge();
    }
}
