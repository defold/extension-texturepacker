// License MIT
// Copyright 2023 Defold Foundation (www.defold.com)

package com.dynamo.bob.pipeline.tp;

import com.dynamo.bob.Project;
import com.dynamo.bob.plugin.IPlugin;
import com.dynamo.bob.util.TextureUtil;

import com.dynamo.texturepacker.proto.Info;


public class Plugin implements IPlugin {

    public void init(Project project) {
        TextureUtil.registerAtlasFileType(".tpatlas");
    }

    public void exit(Project project) {
    }
}
