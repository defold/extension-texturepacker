// License MIT
// Copyright 2023 Defold Foundation (www.defold.com)

package com.dynamo.bob.pipeline;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.ObjectMapper;

import com.dynamo.bob.pipeline.TPAtlas.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.File;

public class TPJsonReader {

    static public Page createPageFromJson(ObjectMapper objectMapper, JsonNode root) throws IOException {
        JsonNode meta = root.get("meta");
        System.out.printf("JSON: %s\n", meta.toString());

        // String s = root.toString();
        // System.out.printf("JSON: %s\n", s);
        //String color = jsonNode.get("color").asText();

        Page page = new Page();

        page.imagePath = meta.get("image").asText();

        //"related_multi_packs": [ "basic-0.tpjson", "basic-2.tpjson", "basic-3.tpjson" ],

        // refers to the other
        JsonNode relatedMultiPacks = meta.get("related_multi_packs");
        page.relatedMultiPacks = objectMapper.readValue(relatedMultiPacks, String[].class);

        JsonNode frames = root.get("frames");
        page.frames = objectMapper.readValue(frames, Frame[].class);

        Size size = objectMapper.readValue(meta.get("size"), Size.class);

        // List<Frame> frames = createFrames(jsonNode.get("color"));
        // Car car = objectMapper.readValue(carJson, Car.class);
        return page;
    }

    static public Page createPage(String json) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(json);
        return createPageFromJson(objectMapper, root);
    }

    static public Page createPage(File file) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(file);
        return createPageFromJson(objectMapper, root);
    }

    static public Page createPage(byte[] json) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(json);
        return createPageFromJson(objectMapper, root);
    }
}
