# How to build the plugin

## Editor extension

The Editor and Bob pipelines use only a .jar file.
To build these, use the `./utils/build_plugins.sh`:

    ./utils/build_plugins.sh arm64-macos

As always, see `build_plugins.sh` for details of Bob, versions and build server.

### Testing the plugin

To test the plugin, use the `./utils/test_plugin.sh <.tpatlas/.tpinfo file>`:

    ./utils/test_plugin.sh ./examples/anim_trim/anim_trim.tpinfo
