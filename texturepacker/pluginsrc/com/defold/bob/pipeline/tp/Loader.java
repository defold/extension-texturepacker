// License MIT
// Copyright 2023 Defold Foundation (www.defold.com)

package com.dynamo.bob.pipeline;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.ObjectMapper;

import com.dynamo.bob.pipeline.Atlas.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.File;

import com.dynamo.texturepacker.proto.Info;

import com.google.protobuf.TextFormat;

// public class ProtoUtil {

//     public static void merge(IResource input, Builder builder) throws IOException, CompileExceptionError {
//         byte[] content = input.getContent();
//         if (content == null) {
//             if (!input.exists()) {
//                 throw new CompileExceptionError(input, 0, "Resource does not exist");
//             }
//             else {
//                 throw new CompileExceptionError(input, 0, "Resource is empty");
//             }
//         }
//         try {
//             TextFormat.merge(new String(input.getContent()), builder);
//         } catch (TextFormat.ParseException e) {
//             // 1:7: String missing ending quote.
//             Pattern pattern = Pattern.compile("(\\d+):(\\d+): (.*)");
//             Matcher m = pattern.matcher(e.getMessage());
//             if (m.matches()) {
//                 throw new CompileExceptionError(input, Integer.parseInt(m.group(1)), m.group(3), e);
//             } else {
//                 throw new CompileExceptionError(input, 0, e.getMessage(), e);
//             }
//         }
//     }
// }


public class Loader {

    // static public Page createPageFromJson(ObjectMapper objectMapper, JsonNode root) throws IOException {
    //     JsonNode meta = root.get("meta");
    //     System.out.printf("JSON: %s\n", meta.toString());

    //     // String s = root.toString();
    //     // System.out.printf("JSON: %s\n", s);
    //     //String color = jsonNode.get("color").asText();

    //     Page page = new Page();

    //     page.imagePath = meta.get("image").asText();

    //     //"related_multi_packs": [ "basic-0.tpjson", "basic-2.tpjson", "basic-3.tpjson" ],

    //     // refers to the other
    //     JsonNode relatedMultiPacks = meta.get("related_multi_packs");
    //     page.relatedMultiPacks = objectMapper.readValue(relatedMultiPacks, String[].class);

    //     JsonNode frames = root.get("frames");
    //     page.frames = objectMapper.readValue(frames, Frame[].class);

    //     Size size = objectMapper.readValue(meta.get("size"), Size.class);

    //     // List<Frame> frames = createFrames(jsonNode.get("color"));
    //     // Car car = objectMapper.readValue(carJson, Car.class);
    //     return page;
    // }

    // static public Page createPage(String json) throws IOException {
    //     ObjectMapper objectMapper = new ObjectMapper();
    //     JsonNode root = objectMapper.readTree(json);
    //     return createPageFromJson(objectMapper, root);
    // }

    // static public Page createPage(File file) throws IOException {
    //     ObjectMapper objectMapper = new ObjectMapper();
    //     JsonNode root = objectMapper.readTree(file);
    //     return createPageFromJson(objectMapper, root);
    // }

    // static public Page createPage(byte[] json) throws IOException {
    //     ObjectMapper objectMapper = new ObjectMapper();
    //     JsonNode root = objectMapper.readTree(json);
    //     return createPageFromJson(objectMapper, root);
    // }

    static public Info.Atlas load(byte[] data) throws IOException {
        Info.Atlas.Builder builder = Info.Atlas.newBuilder();
        TextFormat.merge(new String(data), builder);
        return builder.build();
    }

    static public Info.Atlas load(File file) throws IOException {
        Info.Atlas.Builder builder = Info.Atlas.newBuilder();
        try {
            String content = Files.readString(file.toPath());
            TextFormat.merge(content, builder);
        } catch (FileNotFoundException e) {
            System.err.printf("File not found: %s\n", file);
            return null;
        }
        return builder.build();
    }
}
