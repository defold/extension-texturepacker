// License MIT
// Copyright 2023 Defold Foundation (www.defold.com

package com.dynamo.bob.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.dynamo.bob.pipeline.BuilderUtil;
import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.ProtoParams;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;
import com.google.protobuf.Message;
import com.dynamo.texturepacker.proto.Atlas.AtlasDesc;

@ProtoParams(srcClass = AtlasDesc.class, messageClass = AtlasDesc.class)
@BuilderParams(name="TexturePackerAtlas", inExts=".tpatlas", outExt=".tpatlasc")
public class AtlasBuilder extends Builder<Void> {

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        Task.TaskBuilder<Void> taskBuilder = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input);


        //        .addOutput(input.changeExt(params.outExt()));

        AtlasDesc.Builder builder = AtlasDesc.newBuilder();
        ProtoUtil.merge(input, builder);

        if (!builder.getFile().equals("")) {
            BuilderUtil.checkResource(this.project, input, "file", builder.getFile());

            // Read all the inputs:
            // * other .json files
            taskBuilder.addInput(input.getResource(builder.getFile()));
        }

        //BuilderUtil.checkResource(this.project, input, "atlas", builder.getAtlas());

        //taskBuilder.addInput(this.project.getResource(builder.getAtlas()).changeExt(".a.texturesetc"));
        return taskBuilder.build();
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {

        AtlasDesc.Builder builder = AtlasDesc.newBuilder();
        ProtoUtil.merge(task.input(0), builder);
        //builder.setScene(BuilderUtil.replaceExt(builder.getFile(), ".tpjson", ".tpjsonc"));
        //builder.setAtlas(BuilderUtil.replaceExt(builder.getAtlas(), ".atlas", ".a.texturesetc"));

        // TODO: Create an atlasbuilder, and pass the info to it
        // The output should be one .a.texturesetc and one .texturec (contains all pages)

        // TEMP DUMMY WRITE OUTPUT
        // We should let the AtlasBuilder write the output, given the TPAtlas
        Message msg = builder.build();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        msg.writeTo(out);
        out.close();
        task.output(0).setContent(out.toByteArray());
    }
}
