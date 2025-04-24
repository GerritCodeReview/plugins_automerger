#!/bin/bash

set -euo pipefail
./$1 --config $2 \
  --dir 'plugins/automerger/web/_bazel_ts_out_tests' \
  --test-files 'plugins/automerger/web/_bazel_ts_out_tests/*_test.js' \
  --ts-config="plugins/automerger/web/tsconfig.json"
