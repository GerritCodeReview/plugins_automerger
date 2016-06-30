include_defs('//bucklets/gerrit_plugin.bucklet')
include_defs('//bucklets/maven_jar.bucklet')

gerrit_plugin(
  name = 'automerger',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: automerger',
    'Gerrit-Module: com.googlesource.gerrit.plugins.automerger.Module',
    'Implementation-Title: Automerger plugin',
    'Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/automerger',
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
  labels = ['automerger'],
  source_under_test = [':automerger__plugin'],
  deps = GERRIT_PLUGIN_API + GERRIT_TESTS + [
    ':automerger__plugin',
    '//lib/easymock:easymock',
    '//gerrit-extension-api:api'
  ],
)

java_library(
  name = 'classpath',
  deps = [':automerger__plugin'],
)
