# The .tpinfo format

Now that we have a format for packed images, we can use it for external tools.

## Protobuf format

While the format is defined by a [Protobuf]() format file our TexturePacker exporter is actually just outputting raw text.

The format file is located here: [tpinfo.proto](./texturepacker/pluginsrc/tpinfo.proto)
and we use that to read the `.tpinfo` files.

There is also a [tpatlas.proto](./texturepacker/pluginsrc/tpatlas.proto) if you wish to generate `.tpatlas` files directly.
For instance if you wish to define a lot of animations up front.

## TPInfo format

### Version + Description

If you write your own generator, please output valid information in the `version` and `description` fields.

This will make it a lot easier for everyone to determine which tool (and version thereof) generated the file, and helps when debugging.

### Pages

The format supports using one or more pages.
In the Defold engine, it is assumed that all pages have the same dimensions.
Each page holds the corresponding list of Sprites.

### Sprites

Sprites hold the layout data for each page.
Due to the nature of texture packing, there may be multiple sprites that refer to the same area in the texture page.
Beware though that when generating the sprite layout data, there may be adjacent sprites occupying the texture, so make sure your sprite geometry doesn't intersect with other sprite geometry.

The fields of the Sprite struct (see [tpinfo.proto](./texturepacker/pluginsrc/tpinfo.proto)), is heavily influenced by the [TexturePacker exporter api](https://www.codeandweb.com/texturepacker/documentation/custom-exporter). Documentation for each field is in our proto file, and for further explanation, we refer to their documentation.

#### Polygonal Geometry

Each sprite holds lists of vertices and indices as a result of polygonal packing.

The vertices are specified in source image space. I.e. in pixels, and not rotated.

The indices list holds indices into the vertices list, and every 3 indices form a CCW triangle.

#### Rectangle packing

If the vertices or indices list are empty, a rectangular geometry is automatically generated.

