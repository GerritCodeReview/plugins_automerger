# Gerrit Code Review Automerger Plugin

See src/main/resources/documentation.

## Web Plugin Development

For running unit tests execute:

    bazel test --test_output=all //plugins/automerger/web:karma_test

For checking or fixing eslint formatter problems run:

    bazel test //plugins/automerger/web:lint_test
    bazel run //plugins/automerger/web:lint_bin -- --fix "$(pwd)/plugins/automerger/web"

For testing the plugin with
[Gerrit FE Dev Helper](https://gerrit.googlesource.com/gerrit-fe-dev-helper/)
build the JavaScript bundle and copy it to the `plugins/` folder:

    bazel build //plugins/automerger/web:automerger
    cp -f bazel-bin/plugins/automerger/web/automerger.js plugins/

and let the Dev Helper redirect from `.+/plugins/automerger/static/automerger.js` to
`http://localhost:8081/plugins_/automerger.js`.

