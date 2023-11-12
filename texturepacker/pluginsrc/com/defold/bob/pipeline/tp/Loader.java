// License MIT
// Copyright 2023 Defold Foundation (www.defold.com)

package com.dynamo.bob.pipeline.tp;

import com.dynamo.texturepacker.proto.Info;

import com.google.protobuf.TextFormat;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.File;
import java.nio.file.Files;

public class Loader {

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
