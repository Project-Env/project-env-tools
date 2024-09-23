package io.projectenv.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;

public final class ToolIndexParser {

    private static final Type TOOLS_INDEX_TYPE = new TypeToken<ToolsIndex>() {
    }.getType();

    private static final Gson GSON = new GsonBuilder()
            .enableComplexMapKeySerialization()
            .setPrettyPrinting()
            .create();

    private ToolIndexParser() {
        // noop
    }

    public static ToolsIndex readFrom(File toolsIndexFile) {
        try (Reader reader = new FileReader(toolsIndexFile)) {
            return GSON.fromJson(reader, TOOLS_INDEX_TYPE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeTo(ToolsIndex toolsIndex, File toolsIndexFile) {
        try (Writer writer = new FileWriter(toolsIndexFile)) {
            GSON.toJson(toolsIndex, TOOLS_INDEX_TYPE, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
