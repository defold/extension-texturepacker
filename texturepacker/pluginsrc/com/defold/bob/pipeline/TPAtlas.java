//
// License: MIT
//

package com.dynamo.bob.pipeline;

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

import com.dynamo.bob.pipeline.TPAtlas.Atlas;

import java.util.ArrayList;
import java.util.Arrays;

import java.lang.reflect.Method;

public class TPAtlas {

    public static class Rect {
        public int x;
        public int y;
        public int width;
        public int height;

        public int  getX()      { return x; }
        public void setX(int x) { this.x = x; }
        public int  getY()      { return y; }
        public void setY(int y) { this.y = y; }

        public int  getW()      { return width; }
        public void setW(int w) { width = w; }
        public int  getH()      { return height; }
        public void setH(int h) { height = h; }

        public String toString() {
            return String.format("x: %d, y: %d, width: %d, height: %d", x, y, width, height);
        }
    }

    public static class Size {
        public int width;
        public int height;

        public int  getW()      { return width; }
        public void setW(int w) { width = w; }
        public int  getH()      { return height; }
        public void setH(int h) { height = h; }

        public String toString() {
            return String.format("width: %d, height: %d", width, height);
        }
    }

    public static class Frame {
        public String   filename;
        public Rect     frame;
        public Rect     spriteSourceSize;
        public Size     sourceSize;
        public boolean  rotated;
        public boolean  trimmed;

        public String   getFilename()                   { return filename; }
        public void     setFilename(String filename)    { this.filename = filename; }
        public Rect     getFrame()                      { return frame; }
        public void     setFrame(Rect frame)            { this.frame = frame; }
        public Rect     getSpriteSourceSize()           { return spriteSourceSize; }
        public void     setSpriteSourceSize(Rect spriteSourceSize) { this.spriteSourceSize = spriteSourceSize; }
        public Size     getSourceSize()                 { return sourceSize; }
        public void     setSourceSize(Size sourceSize)  { this.sourceSize = sourceSize; }

        public boolean  getRotated()                    { return rotated; }
        public void     setRotated(boolean rotated)     { this.rotated = rotated; }
        public boolean  getTrimmed()                    { return trimmed; }
        public void     setTrimmed(boolean trimmed)     { this.trimmed = trimmed; }

        public String toString() {
            String s = "Frame {\n";
            s += String.format("    filename: %s\n", filename);
            s += String.format("    frame: %s\n", frame.toString());
            s += String.format("    spriteSourceSize: %s\n", spriteSourceSize.toString());
            s += String.format("    sourceSize: %s\n", sourceSize.toString());
            s += String.format("    rotated: %s\n", rotated?"true":"false");
            s += String.format("    trimmed: %s\n", rotated?"true":"false");
            s += "  }\n";
            return s;
        }
    }

    public static class Page {
        public String       imagePath;// the .png
        public String       descPath; // the .tpjson
        public int          index;
        public Frame[]      frames;
        public String[]     relatedMultiPacks;
    }

    // "meta": {
	// "app": "https://www.codeandweb.com/texturepacker",
	// "version": "1.0",
	// "image": "basic-0.png",
	// "format": "RGBA8888",
	// "size": {"w":196,"h":196},
	// "scale": "1",
	// "related_multi_packs": [ "basic-1.tpjson", "basic-2.tpjson", "basic-3.tpjson" ],
	// "smartupdate": "$TexturePacker:SmartUpdate:89e9e47dab73bc4596bb3f26f50d60de:1fd45822e644a2b9bf64577c3e41bdf7:cc125e5cc6c89c2b0dc975c4f0dfa926$"


    public static class Atlas {
        public List<Page> pages;
    }

    static private void DebugPage(Page page)
    {
        System.out.printf("Page %d:\n", page.index);
        System.out.printf("    imagePath: %s\n", page.imagePath);
        System.out.printf("    descPath: %s\n", page.descPath);

        System.out.printf("  relatedMultiPacks: [");
        for (String s : page.relatedMultiPacks) {
            System.out.printf("%s, ", s);
        }
        System.out.printf("]\n");

        for (Frame frame : page.frames) {
            System.out.printf("  %s\n", frame.toString());
        }
    }


    static private void DebugAtlas(Atlas atlas)
    {
        System.out.printf("Atlas (%d pages):\n", atlas.pages.size());
        for (Page page : atlas.pages) {
            DebugPage(page);
        }
    }

    // ./utils/test_plugin.sh <.tpatlas/.tpjson path>
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");

        if (args.length < 1) {
            System.err.printf("Usage: ./utils/test_plugin.sh <.tpatlas/.tpjson path>\n");
            return;
        }

        String path = args[0];       // .tpjson
        File file = new File(path);
        if (!file.exists())
            throw new IOException(String.format("FIle does not exist: %s", path));

        long timeStart = System.currentTimeMillis();

        Page page = TPJsonReader.createPage(file);
        page.descPath = file.getAbsolutePath();
        page.imagePath = new File(file.getParentFile(), page.imagePath).getAbsolutePath();

        //DebugPage(page);

        Atlas atlas = new Atlas();
        atlas.pages = new ArrayList<>();
        atlas.pages.add(page);

        for (String relatedPage : page.relatedMultiPacks) {
            // Read the other pages
            File otherPageFile = new File(file.getParentFile(), relatedPage);

            Page nextPage = TPJsonReader.createPage(otherPageFile);
            nextPage.descPath = otherPageFile.getAbsolutePath();
            nextPage.imagePath = new File(otherPageFile.getParentFile(), nextPage.imagePath).getAbsolutePath();
            nextPage.index = atlas.pages.size();

            atlas.pages.add(nextPage);
        }


        DebugAtlas(atlas);

        long timeEnd = System.currentTimeMillis();

        System.out.printf("Loaded %s %s\n", path, page!=null ? "ok":"failed");
        System.out.printf("Loading took %d ms\n", (timeEnd - timeStart));

        System.out.printf("--------------------------------\n");

        // System.out.printf("Num animations: %d\n", rive_file.animations.length);
        // for (String animation : rive_file.animations)
        // {
        //     PrintIndent(1);
        //     System.out.printf("%s\n", animation);
        // }
        System.out.printf("--------------------------------\n");
    }
}
