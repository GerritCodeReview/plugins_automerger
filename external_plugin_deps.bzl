load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
  maven_jar(
    name = 'yaml',
    artifact = 'org.yaml:snakeyaml:1.17',
    sha1 = '7a27ea250c5130b2922b86dea63cbb1cc10a660c',
  )

  maven_jar(
    name = 'mockito',
    artifact = 'org.mockito:mockito-all:1.10.19',
    sha1 = '539df70269cc254a58cccc5d8e43286b4a73bf30',
  )

  maven_jar(
    name = 're2j',
    artifact = 'com.google.re2j:re2j:1.0',
    sha1 = 'd24ac5f945b832d93a55343cd1645b1ba3eca7c3',
  )
