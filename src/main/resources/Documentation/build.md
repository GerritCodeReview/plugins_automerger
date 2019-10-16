Build
=====

This plugin is built using Bazel.
Only the Gerrit in-tree build is supported.

Clone or link this plugin to the plugins directory of Gerrit's source
tree.

```
  git clone https://gerrit.googlesource.com/gerrit
  git clone https://gerrit.googlesource.com/plugins/automerger
  cd gerrit/plugins
  ln -s ../../automerger .
```

Put the external dependency Bazel build file into the Gerrit /plugins
directory, replacing the existing empty one.

```
  cd gerrit/plugins
  rm external_plugin_deps.bzl
  ln -s automerger/external_plugin_deps.bzl .
```

From Gerrit source tree issue the command:

```
  bazel build plugins/automerger
```

The output is created in

```
  bazel-bin/plugins/automerger/automerger.jar
```

To execute the tests run:

```
  bazel test plugins/automerger:automerger_tests
```

or filtering using the comma separated tags:

````
  bazel test --test_tag_filters=automerger //...
````

This project can be imported into the Eclipse IDE.
Add the plugin name to the `CUSTOM_PLUGINS` set in
Gerrit core in `tools/bzl/plugins.bzl`, and execute:

```
  ./tools/eclipse/project.py
```
