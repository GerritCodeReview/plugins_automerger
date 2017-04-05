"""External plugin dependencies for the Automerger plugin."""
load('//tools/bzl:maven_jar.bzl', 'maven_jar')

def external_plugin_deps():
  maven_jar(
    name = 're2j',
    artifact = 'com.google.re2j:re2j:1.0',
    sha1 = 'd24ac5f945b832d93a55343cd1645b1ba3eca7c3',
  )
