//
// License: MIT
//

package com.dynamo.bob.pipeline.tp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.awt.image.BufferedImage;

import com.dynamo.bob.textureset.TextureSetGenerator;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.textureset.TextureSetLayout;
import com.dynamo.graphics.proto.Graphics.TextureImage;
import com.dynamo.graphics.proto.Graphics.TextureProfile;
import com.dynamo.texturepacker.proto.Info;
import com.google.protobuf.TextFormat;

import com.dynamo.bob.pipeline.tp.AtlasBuilder.MappedAnimIterator;

public class Atlas {

    public Info.Atlas                       atlas;      // Is this really needed?
    public List<String>                     frameIds;   // The unique frame names
    public List<TextureSetLayout.Page>      pages;
    public List<TextureSetLayout.Layout>    layouts;
    public List<AtlasBuilder.MappedAnimDesc> animations;

    public List<String>                     pageImageNames; // List of base filenames: basic-0.png, ...

    // TODO: Create helper struct for the editor to hold all the info
    static public Atlas createAtlas(String path, byte[] data) throws IOException {
        System.out.printf("Creating atlas: %s\n", path);

        Info.Atlas atlasIn = Info.Atlas.newBuilder().mergeFrom(data).build();

        Atlas atlas = new Atlas();

        atlas.frameIds = AtlasBuilder.getFrameIds(atlasIn);
        atlas.animations = AtlasBuilder.createSingleFrameAnimations(atlas.frameIds);
        atlas.pages = AtlasBuilder.createPages(atlasIn);
        atlas.layouts = TextureSetLayout.createTextureSet(atlas.pages);

        atlas.pageImageNames = new ArrayList<>();
        for (Info.Page page : atlasIn.getPagesList()) {
            atlas.pageImageNames.add(page.getName());
        }

        // // TODO: Use a setting in .tpatlas / array_texture
        // TextureImage.Type textureImageType = TextureImage.Type.TYPE_2D_ARRAY;

        return atlas;
    }

    static public TextureSetResult createTextureSet(Atlas atlas) {
        MappedAnimIterator animIterator = new MappedAnimIterator(atlas.animations, atlas.frameIds);
        return TextureSetGenerator.createTextureSet(atlas.layouts, animIterator);
    }

    // static public TextureImage createTexture(Atlas atlas, List<BufferedImage> textureImages, boolean compress, TextureProfile textureProfile) {
    //     // TODO: Use a setting in .tpatlas / array_texture
    //     TextureImage.Type textureImageType = TextureImage.Type.TYPE_2D_ARRAY;
    //     return TextureUtil.createMultiPageTexture(textureImages, textureImageType, textureProfile, compress);
    // }

    private Info.Atlas createDebugAtlas() {
        Info.Atlas.Builder atlasBuilder = Info.Atlas.newBuilder();

        Info.Page.Builder pageBuilder = Info.Page.newBuilder();

        pageBuilder.setName("page-0");

        Info.Sprite.Builder spriteBuilder = Info.Sprite.newBuilder();
        spriteBuilder.setName("sprite0");
        spriteBuilder.setTrimmed(false);
        spriteBuilder.setRotated(false);
        spriteBuilder.setIsSolid(false);
        spriteBuilder.setIsSolid(false);

        Info.Point.Builder pointBuilder = Info.Point.newBuilder();
        pointBuilder.setX(3);
        pointBuilder.setY(4);
        Info.Point point = pointBuilder.build();

        Info.Size.Builder sizeBuilder = Info.Size.newBuilder();
        sizeBuilder.setWidth(2);
        sizeBuilder.setHeight(2);
        Info.Size size = sizeBuilder.build();

        Info.Rect.Builder rectBuilder = Info.Rect.newBuilder();
        rectBuilder.setX(1);
        rectBuilder.setY(1);
        rectBuilder.setWidth(2);
        rectBuilder.setHeight(2);
        Info.Rect rect = rectBuilder.build();

        spriteBuilder.setUntrimmedSize(size);
        spriteBuilder.setCornerOffset(point);
        spriteBuilder.setSourceRect(rect);
        spriteBuilder.setFrameRect(rect);

        pageBuilder.addSprites(spriteBuilder.build());

        atlasBuilder.addPages(pageBuilder.build());

        return atlasBuilder.build();
    }

    // ./utils/test_plugin.sh <.tpatlas/.tpinfo path>
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");

        if (args.length < 1) {
            System.err.printf("Usage: ./utils/test_plugin.sh <.tpatlas/.tpinfo path>\n");
            return;
        }

        String path = args[0];       // .tpjson
        File file = new File(path);
        if (!file.exists())
            throw new IOException(String.format("FIle does not exist: %s", path));

        long timeStart = System.currentTimeMillis();

        Info.Atlas atlas = Loader.load(file);

        long timeEnd = System.currentTimeMillis();

        System.out.printf("Loaded %s %s\n", path, atlas!=null ? "ok":"failed");
        System.out.printf("Loading took %d ms\n", (timeEnd - timeStart));

        System.out.printf("--------------------------------\n");

        if (atlas != null) {
            System.out.printf("%s\n", TextFormat.printToString(atlas));
        }

        System.out.printf("--------------------------------\n");
    }
}
