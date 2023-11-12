//
// License: MIT
//

package com.dynamo.bob.pipeline.tp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import java.util.ArrayList;
import java.util.Arrays;

import java.lang.reflect.Method;

import com.dynamo.texturepacker.proto.Info;

import com.google.protobuf.TextFormat;

public class Atlas {

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
