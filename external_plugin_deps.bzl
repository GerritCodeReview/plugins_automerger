"""External plugin dependencies for the Automerger plugin."""
load('//tools/bzl:maven_jar.bzl', 'maven_jar')

def external_plugin_deps():
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
