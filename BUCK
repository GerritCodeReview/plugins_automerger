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
    ':re2j',
    ':yaml',
  ],
)

define_license(name = 're2j')

maven_jar(
  name = 'yaml',
  id = 'org.yaml:snakeyaml:1.17',
  sha1 = '7a27ea250c5130b2922b86dea63cbb1cc10a660c',
  license = 'Apache2.0',
)

maven_jar(
  name = 'mockito',
  id = 'org.mockito:mockito-all:1.10.19',
  sha1 = '539df70269cc254a58cccc5d8e43286b4a73bf30',
  license = 'DO_NOT_DISTRIBUTE',
)

maven_jar(
  name = 're2j',
  id = 'com.google.re2j:re2j:1.0',
  sha1 = 'd24ac5f945b832d93a55343cd1645b1ba3eca7c3',
  license = 're2j',
  local_license = True,
)

java_test(
  name = 'automerger_tests',
  srcs = glob(['src/test/java/**/*.java']),
  resources = glob(['src/test/resources/**/*']),
  labels = ['automerger'],
  deps = GERRIT_PLUGIN_API + GERRIT_TESTS + [
    ':automerger__plugin',
    ':mockito',
  ],
)

java_library(
  name = 'classpath',
  deps = [':automerger__plugin'] + GERRIT_PLUGIN_API,
)
