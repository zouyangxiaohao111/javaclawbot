package agent.tool.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class JacksonMcpJsonMapper implements McpJsonMapper {

    private final JsonMapper jsonMapper;

    public JacksonMcpJsonMapper(JsonMapper jsonMapper) {
        if (jsonMapper == null) {
            throw new IllegalArgumentException("JsonMapper must not be null");
        }
        this.jsonMapper = jsonMapper;
    }

    public JsonMapper getJsonMapper() {
        return this.jsonMapper;
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        try {
            content = structToJson(content);
            return this.jsonMapper.readValue(content, type);
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to read value", ex);
        }
    }

    private static @NotNull String structToJson(String content) {
        if (content.endsWith(",")) {
            content = content.substring(0, content.length() - 1);
        }
        return content;
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        try {
            return this.jsonMapper.readValue(content, type);
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to read value", ex);
        }
    }

    String windowContent = "Active code page: 65001";

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        JavaType javaType = this.jsonMapper.getTypeFactory().constructType(type.getType());
        try {
            content = structToJson(content);
            if (content.equalsIgnoreCase(windowContent)) {
                return null;
            }
            return this.jsonMapper.readValue(content, javaType);
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to read value", ex);
        }
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        JavaType javaType = this.jsonMapper.getTypeFactory().constructType(type.getType());
        try {
            return this.jsonMapper.readValue(content, javaType);
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to read value", ex);
        }
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> type) {
        return this.jsonMapper.convertValue(fromValue, type);
    }

    @Override
    public <T> T convertValue(Object fromValue, TypeRef<T> type) {
        JavaType javaType = this.jsonMapper.getTypeFactory().constructType(type.getType());
        return this.jsonMapper.convertValue(fromValue, javaType);
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        try {
            return this.jsonMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to write value as string", ex);
        }
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        try {
            return this.jsonMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IOException("Failed to write value as bytes", ex);
        }
    }
}