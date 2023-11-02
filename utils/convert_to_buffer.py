#!/usr/bin/env python

import os, sys

TEMPLATE_START="""
[
    {
        "name": "data",
        "type": "uint8",
        "count": 1,
        "data": [
"""

TEMPLATE_END="""
        ]
    }
]
"""


def output_data(f, data):
    index = 0
    length = len(data)

    indent = "            "
    s = indent
    for d in data:
        index += 1

        s += "%d" % ord(d)
        if index < length:
            s += ","

        if (index%64) == 0:
            s += "\n" + indent

    f.write(TEMPLATE_START)
    f.write(s)
    f.write(TEMPLATE_END)


def usage():
    print("Usage: convert_to_file.py <input> <output>")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        usage()
        sys.exit(1)

    outpath = None
    if len(sys.argv) >= 3:
        outpath = sys.argv[2]

    inpath = sys.argv[1]
    with open(inpath, 'rb') as f:
        data = f.read()

    with open(outpath, 'wb') as f:
        output_data(f, data)

