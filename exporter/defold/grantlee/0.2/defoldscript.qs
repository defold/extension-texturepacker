function ensureInt(value) {
	const intValue = parseInt(value, 10);
	return isNaN(intValue) ? 0 : intValue;
}

function indent(count) {
	return '  '.repeat(count);
}

function quote(str) {
	return '"' + str + '"';
}

function field(fieldName, value, identLevel = 0, isQuoted = false) {
	return indent(identLevel) + fieldName + ': ' + (isQuoted ? quote(value) : value);
}

var exportRect = function(output, indentLevel, fieldName, rect)
{
	output.push(
		indent(indentLevel) + fieldName + " {",
			field("x", rect.x, indentLevel+1),
			field("y", rect.y, indentLevel+1),
			field("width", rect.width, indentLevel+1),
			field("height", rect.height, indentLevel+1),
		indent(indentLevel) + "}",
	);
}

var exportSize = function(output, indentLevel, fieldName, size)
{
	output.push(
		indent(indentLevel) + fieldName + " {",
			field("width", size.width, indentLevel+1),
			field("height", size.height, indentLevel+1),
		indent(indentLevel) + "}",
	);
}

var exportPoint = function(output, indentLevel, fieldName, vertex)
{
	output.push(
		indent(indentLevel) + fieldName + " {",
			field("x", vertex.x, indentLevel+1),
			field("y", vertex.y, indentLevel+1),
		indent(indentLevel) + "}",
	);
}

var exportIntArray = function(output, indentLevel, fieldName, arr)
{
	let s = indent(indentLevel) + fieldName + ": [";

	for (let i = 0; i < arr.length; ++i) {
		s += arr[i];
		if (i < arr.length-1)
		{
			s += ", ";
		}
	}

	output.push(s + "]");
}

// https://www.codeandweb.com/texturepacker/documentation/custom-exporter#sprite-type
var exportSprite = function(output, indentLevel, sprite)
{
	output.push(
		indent(indentLevel) + "sprites {",
		field("name", sprite.trimmedName, indentLevel+1, true),
		field("trimmed", sprite.trimmed, indentLevel+1),
		field("rotated", sprite.rotated, indentLevel+1),
		field("is_solid", sprite.isSolid, indentLevel+1),
	)

	//exportPoint(output, indentLevel+1, "centerOffset", sprite.centerOffset); // don't think we need this one
	exportPoint(output, indentLevel+1, "corner_offset", sprite.cornerOffset);
	exportRect(output, indentLevel+1, "source_rect", sprite.sourceRect);
	exportRect(output, indentLevel+1, "frame_rect", sprite.frameRect);
	exportSize(output, indentLevel+1, "untrimmed_size", sprite.untrimmedSize);

	for (let i = 0; i < sprite.vertices.length; ++i) {
		let vertex = sprite.vertices[i];
		exportPoint(output, indentLevel+1, "vertices", vertex);
	}

	exportIntArray(output, indentLevel+1, "indices", sprite.triangleIndices);

	output.push(indent(indentLevel) + "}");
}

var exportPage = function(output, indentLevel, texture)
{
	// var textureName = texture.fullName;
	output.push(
		indent(indentLevel) + "pages {",
		field("name", texture.fullName, indentLevel+1, true),
	);

	exportSize(output, indentLevel+1, "size", texture.size);

    for (let i = 0; i < texture.allSprites.length; i++)
    {
		exportSprite(output, indentLevel+1, texture.allSprites[i]);
    }

	output.push(indent(indentLevel) + "}");
}

var exportAtlas = function(root)
{
	let output = [];
	output.push(
		"# Exported by Defold Exporter",
		"# For documentation of the fields: https://www.codeandweb.com/texturepacker/documentation/custom-exporter#sprite-type",
		""
	);

	output.push("version: \"1.1\""); 							  // our file format version
	output.push("description: \"Exported using TexturePacker\""); // The tool used

    let textures = root.allResults[root.variantIndex].textures;
    for (let i = 0; i < textures.length; i++)
    {
		exportPage(output, 0, textures[i]);
    }
	return output;
}


var ExportData = function(root)
{
    var output = exportAtlas(root);
	return output.join('\n');
}
ExportData.filterName = "ExportData";
Library.addFilter("ExportData");
