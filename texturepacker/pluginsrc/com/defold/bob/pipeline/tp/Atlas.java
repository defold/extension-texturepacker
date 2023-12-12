//
// License: MIT
//

package com.dynamo.bob.pipeline.tp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.awt.image.BufferedImage;

import com.dynamo.bob.textureset.TextureSetGenerator;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.textureset.TextureSetLayout;
import com.dynamo.texturepacker.proto.Info;
import com.google.protobuf.TextFormat;

import com.dynamo.bob.pipeline.tp.AtlasBuilder.MappedAnimIterator;

import com.dynamo.bob.util.TextureUtil;
import com.dynamo.bob.pipeline.TextureGeneratorException;

import com.dynamo.graphics.proto.Graphics.TextureImage;
import com.dynamo.graphics.proto.Graphics.TextureProfile;
import com.dynamo.gamesys.proto.TextureSetProto.TextureSet;

public class Atlas {

    public List<String>                     frameIds;   // The unique frame names
    public List<TextureSetLayout.Page>      pages;
    public List<TextureSetLayout.Layout>    layouts;
    public List<AtlasBuilder.MappedAnimDesc> animations;

    public List<String>                     pageImageNames; // List of base filenames: basic-0.png, ...

    // TODO: Create helper struct for the editor to hold all the info
    static public Atlas createAtlasInternal(String path, Info.Atlas atlasIn) throws IOException {
        System.out.printf("Creating atlas: %s\n", path);

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

    static public Atlas createAtlas(String path, byte[] data) throws IOException {
        System.out.printf("Creating atlas: %s\n", path);
        Info.Atlas atlasIn = Info.Atlas.newBuilder().mergeFrom(data).build();
        return createAtlasInternal(path, atlasIn);
    }


    // Used from editor
    static public TextureSet createTextureSet(String path, Atlas atlas, String texture) {
        MappedAnimIterator animIterator = new MappedAnimIterator(atlas.animations, atlas.frameIds);
        TextureSetResult result = TextureSetGenerator.createTextureSet(atlas.layouts, animIterator);
        int pageCount = atlas.pages.size();
        return result.builder
                    .setPageCount(pageCount)
                    .setTexture(texture)
                    .build();
    }

    // Used from editor
    static public TextureImage createTexture(String path, Atlas atlas, BufferedImage[] textureImages, TextureProfile textureProfile) throws TextureGeneratorException {
        // TODO: Use a setting in .tpatlas / array_texture
        TextureImage.Type textureImageType = TextureImage.Type.TYPE_2D_ARRAY;
        boolean compress = textureProfile != null;
        //createMultiPageTexture(List<BufferedImage> images, TextureImage.Type textureType, TextureProfile texProfile, boolean compress)
        return TextureUtil.createMultiPageTexture(Arrays.asList(textureImages), textureImageType, textureProfile, compress);
    }

    // Used from editor
    // returns an array of floats (flattened (x,y)-tuples): [x0,y0,x1,y1,x2,...]
    static public float[] getTriangles(TextureSetLayout.SourceImage image, Float pageHeight) {
        float half_width = image.rect.width * 0.5f;
        float half_height = image.rect.height * 0.5f;
        float centerx = image.rect.x + half_width;
        float centery = image.rect.y + half_height;

        //System.out.printf("image %s: %f, %f, %f, %f  %f\n", image.name, image.rect.x, image.rect.y, image.rect.width, image.rect.height, pageHeight);

        float[] out = new float[image.indices.size() * 2];
        int i = 0;
        for (int index : image.indices) {
            // The vertices are in image local space. Upright, regardless of rotation
            TextureSetLayout.Point p = image.vertices.get(index);

            //System.out.printf("p: %f, %f\n", p.x, p.y);

            // make it local around the center point
            float x = p.x - half_width;
            float y = p.y - half_height;
            //System.out.printf("  local: %f, %f\n", x, y);

            // flip y in the local space
            y *= -1;
            //System.out.printf("  flipped: %f, %f\n", x, y);

            // offset it into the page
            x = centerx + x;
            y = centery + y;
            //System.out.printf("  center: %f, %f\n", x, y);

            // flip y in the page space
            y = pageHeight - y;
            //System.out.printf("  page: %f, %f\n", x, y);

            out[i++] = x;
            out[i++] = y;
        }
        return out;
    }

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
            throw new IOException(String.format("File does not exist: %s", path));

        {
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

        {
            Info.Atlas atlasIn = Loader.load(file);
            Atlas atlas = Atlas.createAtlasInternal(path, atlasIn);

            for (TextureSetLayout.Page page : atlas.pages) {
                for (TextureSetLayout.SourceImage image : page.images) {
                    //System.out.printf("IMAGE: %s\n", image.name);
                    boolean found = image.name.equals("box_fill_64") || image.name.equals("triangle_fill_64");
                    if (!found)
                        continue;
                    System.out.printf("\nFOUND: %s\n", image.name);

                    float[] vertices = Atlas.getTriangles(image, page.size.height);

                    for (int i = 0; i < vertices.length; ++i) {
                        System.out.printf("%f ", vertices[i]);

                        if ((i % 2) == 1) {
                            System.out.printf("\n");
                        }
                    }

                    System.out.printf("\n");
                    TextureSetLayout.Rectangle rect = image.rect;

                    System.out.printf("x/y/w/h: %f, %f  %f, %f\n",
                            rect.x, rect.y, rect.width, rect.height);
                }
            }
        }
    }
}
