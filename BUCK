include_defs('//bucklets/gerrit_plugin.bucklet')
include_defs('//bucklets/maven_jar.bucklet')

# MODULE = 'com.googlesource.gerrit.plugins.automerger.HelloForm'

gerrit_plugin(
  name = 'automerger-plugin',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/**/*']),
#   gwt_module = MODULE,
  manifest_entries = [
    'Gerrit-PluginName: automerger',
    'Gerrit-Module: com.googlesource.gerrit.plugins.automerger.Module',
    'Implementation-Title: Automerger plugin',
    'Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/automerger-plugin',
  ],
  deps = [
    ':yaml',
  ],
)

maven_jar(
  name = 'yaml',
  id = 'org.yaml:snakeyaml:1.17',
  license = 'Apache2.0',
)

java_test(
  name = 'automerger_tests',
  srcs = glob(['src/test/java/**/*IT.java']),
  resources = glob(['src/test/resources/**/*']),
  labels = ['automerger-plugin'],
  source_under_test = [':automerger-plugin__plugin'],
  deps = GERRIT_PLUGIN_API + GERRIT_TESTS + [
    ':automerger-plugin__plugin',
    '//lib/easymock:easymock'
  ],
)

java_library(
  name = 'classpath',
  deps = [':automerger-plugin__plugin'],
)
