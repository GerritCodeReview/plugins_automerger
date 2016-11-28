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

/**
 * Exception class for merge conflicts.
 */
class FailedMergeException extends Exception {
  private static final int MAX_CONFLICT_MESSAGE_LENGTH = 10000;

  public final Map<String, String> failedMerges;

  FailedMergeException(Map<String, String> failedMerges) {
    this.failedMerges = failedMerges;
  }

  /**
   * Display all conflicts for a change. Truncate at MAX_CONFLICT_MESSAGE_LENGTH.
   * @return A string representation of the conflicts.
   */
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
      if (message.length() > MAX_CONFLICT_MESSAGE_LENGTH) {
        conflictMessage = message.substring(0, MAX_CONFLICT_MESSAGE_LENGTH);
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

  /**
   * Get the branches that we failed to merge to.
   * @return The comma-separated branches that we failed to merge to.
   */
  public String failedMergeKeys() {
    return Joiner.on(", ").join(failedMerges.keySet());
  }
}
