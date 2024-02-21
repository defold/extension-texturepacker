// License MIT
// Copyright 2023 Defold Foundation (www.defold.com

package com.dynamo.bob.pipeline.tp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.awt.image.BufferedImage;

import com.dynamo.bob.pipeline.BuilderUtil;
import com.dynamo.bob.pipeline.ProtoUtil;

import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.ProtoParams;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;

import com.dynamo.bob.pipeline.TextureGeneratorException;
import com.dynamo.bob.textureset.TextureSetGenerator;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.textureset.TextureSetLayout;
import com.dynamo.bob.util.TextureUtil;

// Formats

// BOB
import com.dynamo.graphics.proto.Graphics.TextureImage;
import com.dynamo.graphics.proto.Graphics.TextureProfile;
import com.dynamo.gamesys.proto.TextureSetProto.TextureSet; // Final engine format
import com.dynamo.gamesys.proto.Tile.Playback;
import com.dynamo.gamesys.proto.Tile.SpriteTrimmingMode;

// Texture packer extension
import com.dynamo.texturepacker.proto.Info;                 // The Texture Packer input format
import com.dynamo.texturepacker.proto.Atlas.AtlasDesc;      // The high level information
import com.dynamo.texturepacker.proto.Atlas.AtlasAnimation; // A flipbook animation

import com.google.protobuf.TextFormat; // Debug

@ProtoParams(srcClass = AtlasDesc.class, messageClass = AtlasDesc.class)
@BuilderParams(name="TexturePackerAtlas", inExts=".tpatlas", outExt = ".a.texturesetc")
public class AtlasBuilder extends Builder<Void> {

    static final String TEMPLATE_PATH = "texturepacker/editor/resources/templates/template.tpatlas";

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {

        // Since these template files currently get compiled by bob
        if (input.getPath().equals(TEMPLATE_PATH))
        {
            Task.TaskBuilder<Void> taskBuilder = Task.<Void>newBuilder(this);
            return taskBuilder.build();
        }

        Task.TaskBuilder<Void> taskBuilder = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()))
                .addOutput(input.changeExt(".texturec"));

        AtlasDesc.Builder builder = AtlasDesc.newBuilder();
        ProtoUtil.merge(input, builder);

        BuilderUtil.checkResource(this.project, input, "file", builder.getFile());

        IResource infoResource = input.getResource(builder.getFile());
        taskBuilder.addInput(infoResource);

        Info.Atlas infoAtlas = Loader.load(infoResource.getContent());

        for (Info.Page page : infoAtlas.getPagesList()) {
            IResource r = infoResource.getResource(page.getName());
            taskBuilder.addInput(r);
        }

        // If there is a texture profiles file, we need to make sure
        // it has been read before building this tile set, add it as an input.
        String textureProfilesPath = this.project.getProjectProperties().getStringValue("graphics", "texture_profiles");
        if (textureProfilesPath != null) {
            taskBuilder.addInput(this.project.getResource(textureProfilesPath));
        }
        return taskBuilder.build();
    }

    // Borrowed from AtlasUtil.java in bob
    public static class MappedAnimDesc extends TextureSetGenerator.AnimDesc {
        List<String> ids; // Ids of each frame of animation
        private boolean singleFrame;

        public MappedAnimDesc(String id, List<String> ids, Playback playback, int fps,
                                boolean flipHorizontal, boolean flipVertical) {
            super(id, playback, fps, flipHorizontal, flipVertical);
            this.ids = ids;
            this.singleFrame = false;
        }

        public MappedAnimDesc(String id) {
            super(id, Playback.PLAYBACK_NONE, 0, false, false);
            this.ids = new ArrayList<>();
            this.ids.add(id);
            this.singleFrame = true;
        }

        public List<String> getIds() {
            return this.ids;
        }
    }

    public static class MappedAnimIterator implements TextureSetGenerator.AnimIterator {
        final List<MappedAnimDesc> anims;
        final List<String> imageIds; // The ordered list of the single frames
        int nextAnimIndex;
        int nextFrameIndex;

        public MappedAnimIterator(List<MappedAnimDesc> anims, List<String> imageIds) {
            this.anims = anims;
            this.imageIds = imageIds;

            // System.out.printf("Image ids\n");
            // for (String s : imageIds) {
            //     System.out.printf("  image id: %s\n", s);
            // }
        }

        @Override
        public TextureSetGenerator.AnimDesc nextAnim() {
            if (nextAnimIndex < anims.size()) {
                nextFrameIndex = 0;
                return anims.get(nextAnimIndex++);
            }
            return null;
        }

        @Override
        public Integer nextFrameIndex() {
            MappedAnimDesc anim = anims.get(nextAnimIndex - 1);
            if (nextFrameIndex < anim.getIds().size()) {
                int index = imageIds.indexOf(anim.getIds().get(nextFrameIndex++));
                // We only really get here from the Editor,
                // and I've not figured out why this code is called before checking the build errors
                return index < 0 ? null : index;
            }
            return null;
        }

        @Override
        public String getFrameId() {
            MappedAnimDesc anim = anims.get(nextAnimIndex - 1);
            String prefix = "";
            if (!anim.singleFrame)
                prefix = anim.getId() + "/";
            return prefix + anim.getIds().get(nextFrameIndex-1);
        }

        @Override
        public void rewind() {
            nextAnimIndex = 0;
            nextFrameIndex = 0;
        }
    }

    static private TextureSetLayout.Size createSize(Info.Size size) {
        return new TextureSetLayout.Size(size.getWidth(), size.getHeight());
    }
    static private TextureSetLayout.Rectangle createRect(Info.Rect rect) {
        return new TextureSetLayout.Rectangle(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }
    static private TextureSetLayout.Point createPoint(Info.Point point) {
        return new TextureSetLayout.Point(point.getX(), point.getY());
    }

    static private TextureSetLayout.SourceImage createSprite(Info.Sprite srcSprite) {
        TextureSetLayout.SourceImage out = new TextureSetLayout.SourceImage();

        out.name            = srcSprite.getName();
        out.rotated         = srcSprite.getRotated();

        Info.Rect tightRect   = srcSprite.getFrameRect();
        Info.Size originalSze = srcSprite.getUntrimmedSize();
        // The offset from the top left corner of the image, where to find the tight rect
        Info.Point offset     = srcSprite.getCornerOffset();

        out.rect = new TextureSetLayout.Rectangle(tightRect.getX() - offset.getX(), tightRect.getY() - offset.getY(),
                                                  originalSze.getWidth(), originalSze.getHeight());

        out.indices = new ArrayList<>(srcSprite.getIndicesList());
        out.vertices = new ArrayList<>();
        for (Info.Point p : srcSprite.getVerticesList()) {
            TextureSetLayout.Point pout = AtlasBuilder.createPoint(p);
            pout.y = originalSze.getHeight() - pout.y;
            out.vertices.add(pout);
        }

        return out;
    }

    static private TextureSetLayout.Page createPage(int index, Info.Page srcPage) {
        TextureSetLayout.Page page = new TextureSetLayout.Page();
        page.index = index;
        page.name = srcPage.getName();
        page.images = new ArrayList<>();
        page.size = AtlasBuilder.createSize(srcPage.getSize());

        for (Info.Sprite sprite : srcPage.getSpritesList()) {
            page.images.add(AtlasBuilder.createSprite(sprite));
        }
        return page;
    }

    static public List<TextureSetLayout.Page> createPages(Info.Atlas srcAtlas) {
        List<TextureSetLayout.Page> outPages = new ArrayList<>();
        int index = 0;
        for (Info.Page srcPage : srcAtlas.getPagesList()) {
            outPages.add(AtlasBuilder.createPage(index++, srcPage));
        }
        return outPages;
    }

    static public List<String> getFrameIds(Info.Atlas srcAtlas) {
        List<String> ids = new ArrayList<>();
        for (Info.Page srcPage : srcAtlas.getPagesList()) {
            for (Info.Sprite sprite : srcPage.getSpritesList()) {
                ids.add(sprite.getName());
            }
        }
        return ids;
    }

    static public List<MappedAnimDesc> createSingleFrameAnimations(List<String> frameIds) {
        List<MappedAnimDesc> anims = new ArrayList<>();
        for (String id : frameIds) {
            anims.add(new MappedAnimDesc(id));
        }
        return anims;
    }

    static public List<MappedAnimDesc> createFlipBookAnimations(AtlasDesc.Builder tpatlas) {
        List<MappedAnimDesc> anims = new ArrayList<>();
        for (AtlasAnimation animation : tpatlas.getAnimationsList()) {
            anims.add(new MappedAnimDesc(animation.getId(), animation.getImagesList(),
                                            animation.getPlayback(), animation.getFps(),
                                            animation.getFlipHorizontal() != 0,
                                            animation.getFlipVertical() != 0));
        }
        return anims;
    }

    static public List<MappedAnimDesc> createAnimations(AtlasDesc.Builder tpatlas, List<String> frameIds) {
        List<MappedAnimDesc> anims = createSingleFrameAnimations(frameIds);
        anims.addAll(createFlipBookAnimations(tpatlas));
        return anims;
    }

    static public void renameAnimations(AtlasDesc.Builder builder, String renamePatterns) throws CompileExceptionError {
        List<AtlasAnimation> newAnimations = new ArrayList<>();
        for (AtlasAnimation animation : builder.getAnimationsList()) {
            AtlasAnimation.Builder animationBuilder = AtlasAnimation.newBuilder().mergeFrom(animation);
            List<String> originalImages = animationBuilder.getImagesList();
            List<String> newImages = Atlas.renameFrameIds(originalImages, renamePatterns);


            animationBuilder.clearImages();
            animationBuilder.addAllImages(newImages);
            newAnimations.add(animationBuilder.build());
        }
        builder.clearAnimations();
        builder.addAllAnimations(newAnimations);
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {

        AtlasDesc.Builder builder = AtlasDesc.newBuilder();
        ProtoUtil.merge(task.input(0), builder);
        if (task.input(0).getPath().equals(TEMPLATE_PATH)) {
            return;
        }

        Info.Atlas infoAtlas = Loader.load(task.input(1).getContent());

        List<TextureSetLayout.Page> pages = AtlasBuilder.createPages(infoAtlas);

        List<String> frameIds = AtlasBuilder.getFrameIds(infoAtlas); // The unique frames

        // Now rename the images
        String renamePatterns = builder.getRenamePatterns();
        frameIds = Atlas.renameFrameIds(frameIds, renamePatterns);

        renameAnimations(builder, renamePatterns);

        // verify that the animations doesn't refer to an old image
        for (AtlasAnimation animation : builder.getAnimationsList()) {
            for (String image : animation.getImagesList()) {
                if (!frameIds.contains(image)) {
                    throw new CompileExceptionError(task.input(0), -1,
                            String.format("Animation '%s' contains image '%s' that does not exist in file '%s'", animation.getId(), image, task.input(1).getPath()));
                }
            }
        }

        // System.out.printf("FRAME IDS\n");
        // for (String frameId : frameIds) {
        //     System.out.printf("  FRAME ID: %s\n", frameId);
        // }

        List<MappedAnimDesc> animations = createAnimations(builder, frameIds);
        MappedAnimIterator animIterator = new MappedAnimIterator(animations, frameIds);

        List<TextureSetLayout.Layout> layouts = TextureSetLayout.createTextureSet(pages);
        TextureSetResult result = TextureSetGenerator.createTextureSet(layouts, animIterator);

        // If we want better control over it, we can add a setting for it
        TextureImage.Type textureImageType = TextureImage.Type.TYPE_2D_ARRAY;
        if (layouts.size() == 1) {
            if (!builder.getIsPagedAtlas()) {
                textureImageType = TextureImage.Type.TYPE_2D;
            }
        }

        int pageCount = textureImageType == TextureImage.Type.TYPE_2D_ARRAY ? layouts.size() : 0;

        int buildDirLen         = project.getBuildDirectory().length();
        String texturePath      = task.output(1).getPath().substring(buildDirLen);
        TextureSet textureSet   = result.builder.setPageCount(pageCount)
                                                .setTexture(texturePath)
                                                .build();

        TextureProfile texProfile = TextureUtil.getTextureProfileByPath(this.project.getTextureProfiles(), task.input(0).getPath());

        List<IResource> imageResources = new ArrayList<>();
        for (Info.Page page : infoAtlas.getPagesList()) {
            IResource r = task.input(1).getResource(page.getName());
            imageResources.add(r);
        }
        List<BufferedImage> textureImages = TextureUtil.loadImages(imageResources);

        boolean compress = project.option("texture-compression", "false").equals("true");

        TextureImage texture = null;
        try {
            texture = TextureUtil.createMultiPageTexture(textureImages, textureImageType, texProfile, compress);
        } catch (TextureGeneratorException e) {
            throw new CompileExceptionError(task.input(0), -1, e.getMessage(), e);
        }

        //System.out.printf("DEBUG: %s\n", TextFormat.printToString(textureSet));

        task.output(0).setContent(textureSet.toByteArray());
        task.output(1).setContent(texture.toByteArray());
    }
}
