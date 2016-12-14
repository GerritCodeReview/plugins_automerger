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

import java.util.List;

/** Exception class for merge conflicts. */
class FailedMergeException extends Exception {
  public final List<String> failedMerges;

  FailedMergeException(List<String> failedMerges) {
    this.failedMerges = failedMerges;
  }

  /**
   * Display all conflicts for a change. Truncate at MAX_CONFLICT_MESSAGE_LENGTH.
   *
   * @return A string representation of the conflicts.
   */
  public String displayConflicts() {
    return Joiner.on("\n").join(failedMerges);
  }
}
