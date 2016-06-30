include_defs('//bucklets/gerrit_plugin.bucklet')
include_defs('//bucklets/maven_jar.bucklet')

gerrit_plugin(
  name = 'automerger',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: automerger',
    'Gerrit-Module: com.googlesource.gerrit.plugins.automerger.Module',
    'Gerrit-HttpModule: com.googlesource.gerrit.plugins.automerger.HttpModule',
    'Implementation-Title: Automerger plugin',
    'Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/automerger',
  ],
  deps = [
    ':gson',
    ':re2j',
    ':yaml',
  ],
)

define_license(name = 're2j')

maven_jar(
  name = 'yaml',
  id = 'org.yaml:snakeyaml:1.17',
  license = 'Apache2.0',
)

maven_jar(
  name = 'gson',
  id = 'com.google.code.gson:gson:2.6.2',
  license = 'Apache2.0',
)

maven_jar(
  name = 'mockito',
  id = 'org.mockito:mockito-all:1.10.19',
  license = 'DO_NOT_DISTRIBUTE',
)

maven_jar(
  name = 're2j',
  id = 'com.google.re2j:re2j:1.0',
  license = 're2j',
  local_license = True,
)

java_test(
  name = 'automerger_tests',
  srcs = glob(['src/test/java/**/*.java']),
  resources = glob(['src/test/resources/**/*']),
  labels = ['automerger'],
  source_under_test = [':automerger__plugin'],
  deps = GERRIT_PLUGIN_API + GERRIT_TESTS + [
    ':automerger__plugin',
    ':mockito',
    '//lib/easymock:easymock',
    '//gerrit-extension-api:api'
  ],
)

java_library(
  name = 'classpath',
  deps = [':automerger__plugin'] + GERRIT_GWT_API,
)
