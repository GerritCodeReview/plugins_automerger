load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

gerrit_plugin(
    name = "automerger",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: automerger",
        "Gerrit-Module: com.googlesource.gerrit.plugins.automerger.AutomergerModule",
        "Implementation-Title: Automerger plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/automerger",
    ],
    resource_jars = ["//plugins/automerger/web:automerger"],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "@re2j//jar",
    ],
)

java_library(
    name = "automerger_test_helpers",
    srcs = glob(["src/test/java/**/helpers/*.java"]),
)

junit_tests(
    name = "automerger_tests",
    srcs = glob([
        "src/test/java/**/*Test.java",
        "src/test/java/**/*IT.java",
    ]),
    resources = glob(["src/test/resources/**/*"]),
    tags = ["automerger"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":automerger_test_helpers",
        ":automerger__plugin",
        "@commons-net//jar",
    ],
)
