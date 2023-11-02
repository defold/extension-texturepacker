#!/usr/bin/env python

import sys, os

def test_file(path):
    print path
    os.system("./utils/test_plugin.sh %s" % path)


for root, dirs, files in os.walk('.'):
    for f in files:
        if not f.endswith('.riv'):
            continue

        fullpath = os.path.join(root, f)
        test_file(fullpath)
