#!/bin/bash

set -euo pipefail
./$1 start $2 --single-run \
  --root 'plugins/automerger/web/_bazel_ts_out_tests/' \
  --test-files '*_test.js'
