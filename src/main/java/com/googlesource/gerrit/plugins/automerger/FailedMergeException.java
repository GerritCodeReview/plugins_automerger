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
import com.google.gerrit.common.data.ParameterizedString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exception class for merge conflicts. */
class FailedMergeException extends Exception {
  private static final long serialVersionUID = 1L;
  private static final int MAX_CONFLICT_MESSAGE_LENGTH = 10000;
  public final String currentRevision;
  public final String conflictMessage;
  public final String hostName;
  public final String project;
  public final int changeNumber;
  public final int patchsetNumber;
  public final String topic;
  public final Map<String, String> failedMergeBranchMap;

  FailedMergeException(
      Map<String, String> failedMergeBranchMap,
      String currentRevision,
      String hostName,
      String project,
      int changeNumber,
      int patchsetNumber,
      String conflictMessage,
      String topic) {
    this.failedMergeBranchMap = failedMergeBranchMap;
    this.currentRevision = currentRevision;
    this.hostName = hostName;
    this.project = project;
    this.changeNumber = changeNumber;
    this.patchsetNumber = patchsetNumber;
    this.conflictMessage = conflictMessage;
    this.topic = topic;
  }

  /**
   * Display all conflicts for a change. Truncate at MAX_CONFLICT_MESSAGE_LENGTH.
   *
   * @return A string representation of the conflicts.
   */
  public String getDisplayString() {
    List<String> conflictMessages = new ArrayList<>();
    for (Map.Entry<String, String> branchMapEntry : failedMergeBranchMap.entrySet()) {
      String branch = branchMapEntry.getKey();
      String mergeConflictMessage = branchMapEntry.getValue();
      conflictMessages.add(
          assembleConflictMessage(
              conflictMessage, getSubstitutionMap(branch, mergeConflictMessage)));
    }

    return Joiner.on("\n").join(conflictMessages);
  }

  private String assembleConflictMessage(String errorString, Map<String, String> substitutionMap) {
    ParameterizedString pattern = new ParameterizedString(errorString);

    String modifiedString = pattern.replace(substitutionMap);
    if (errorString.length() > MAX_CONFLICT_MESSAGE_LENGTH) {
      modifiedString = modifiedString.substring(0, MAX_CONFLICT_MESSAGE_LENGTH) + "\n...";
    }
    return modifiedString;
  }

  private Map<String, String> getSubstitutionMap(String branch, String mergeConflictMessage) {
    Map<String, String> substitutionMap = new HashMap<>();
    substitutionMap.put("branch", branch);
    substitutionMap.put("revision", currentRevision);
    substitutionMap.put("project", project);
    substitutionMap.put("changeNumber", Integer.toString(changeNumber));
    substitutionMap.put("patchsetNumber", Integer.toString(patchsetNumber));
    substitutionMap.put("conflict", mergeConflictMessage);
    substitutionMap.put("hostname", hostName);
    substitutionMap.put("topic", topic);
    return substitutionMap;
  }
}
