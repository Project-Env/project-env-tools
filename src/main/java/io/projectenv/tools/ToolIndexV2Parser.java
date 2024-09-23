package io.projectenv.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;

public final class ToolIndexV2Parser {

    private static final Type TOOLS_INDEX_TYPE = new TypeToken<ToolsIndexV2>() {
    }.getType();

    private static final Gson GSON = new GsonBuilder()
            .enableComplexMapKeySerialization()
            .setPrettyPrinting()
            .create();

    private ToolIndexV2Parser() {
        // noop
    }

    public static ToolsIndexV2 readFrom(File toolsIndexFile) {
        try (Reader reader = new FileReader(toolsIndexFile)) {
            return GSON.fromJson(reader, TOOLS_INDEX_TYPE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeTo(ToolsIndexV2 toolsIndex, File toolsIndexFile) {
        try (Writer writer = new FileWriter(toolsIndexFile)) {
            GSON.toJson(toolsIndex, TOOLS_INDEX_TYPE, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


}
