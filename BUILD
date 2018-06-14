load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "gerrit_plugin", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS")

gerrit_plugin(
    name = "automerger",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: automerger",
        "Gerrit-Module: com.googlesource.gerrit.plugins.automerger.AutomergerModule",
        "Implementation-Title: Automerger plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/automerger",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "@re2j//jar",
    ],
)

junit_tests(
    name = "automerger_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    resources = glob(["src/test/resources/**/*"]),
    tags = ["automerger"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":automerger__plugin",
        "@commons-net//jar",
    ],
)
