// License MIT
// Copyright 2023 Defold Foundation (www.defold.com

package com.dynamo.bob.pipeline;

import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.pipeline.BuilderUtil;

import com.dynamo.bob.CopyBuilder;

@BuilderParams(name="TexturePackerJson", inExts=".tpjson", outExt=".tpjsonc")
public class TPJsonBuilder extends CopyBuilder {}
