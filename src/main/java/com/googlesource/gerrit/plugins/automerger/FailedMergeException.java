// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.automerger;

import com.google.common.base.Joiner;

import java.util.Map;

class FailedMergeException extends Exception {
  public Map<String, String> failedMerges;
  final int maxConflictMessageLength = 10000;

  FailedMergeException(Map<String, String> failedMerges) {
    this.failedMerges = failedMerges;
  }

  public String displayConflicts() {
    StringBuilder output = new StringBuilder();
    output.append("Merge conflict found on ");
    output.append(failedMergeKeys());
    output.append(". Please follow instructions at go/resolveconflict ");
    output.append("to resolve this merge conflict.\n\n");

    for (Map.Entry<String, String> entry : failedMerges.entrySet()) {
      String branch = entry.getKey();
      String message = entry.getValue();
      String conflictMessage = message;
      boolean truncated = false;
      if (message.length() > maxConflictMessageLength) {
        conflictMessage = message.substring(0, maxConflictMessageLength);
        truncated = true;
      }
      output.append(branch);
      output.append(":\n");
      output.append(conflictMessage);
      if (truncated) {
        output.append("...\n\n");
      }
    }
    return output.toString();
  }

  public String failedMergeKeys() {
    return Joiner.on(", ").join(failedMerges.keySet());
  }
}