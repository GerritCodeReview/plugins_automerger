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

import java.util.Arrays;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;

public class FailedMergeExceptionTest {
  private Map<String, String> failedMergeBranches;
  private String currentRevision;

  @Before
  public void setUp() throws Exception {
    failedMergeBranches = new TreeMap<String, String>();
    failedMergeBranches.put("branch1", "branch1 merge conflict");
    failedMergeBranches.put("branch2", "branch2 merge conflict");
    failedMergeBranches.put("branch3", "branch3 merge conflict");
    currentRevision = "d3adb33f";
  }

  @Test
  public void defaultConflictMessageTest() throws Exception {
    FailedMergeException fme = new FailedMergeException(failedMergeBranches, currentRevision, "");
    String branch1String = "Merge conflict found on branch1";
    String branch2String = "Merge conflict found on branch2";
    String branch3String = "Merge conflict found on branch3";
    List<String> expectedStrings = Arrays.asList(branch1String, branch2String, branch3String);
    assertThat(fme.getDisplayString()).isEqualTo(Joiner.on("\n").join(expectedStrings));
  }

  @Test
  public void customConflictMessageTest() throws Exception {
    FailedMergeException fme =
        new FailedMergeException(failedMergeBranches, currentRevision, "asdf");
    List<String> expectedStrings = Arrays.asList("asdf", "asdf", "asdf");
    assertThat(fme.getDisplayString()).isEqualTo(Joiner.on("\n").join(expectedStrings));
  }

  @Test
  public void customConflictMessageWithRevisionSubstitutionTest() throws Exception {
    FailedMergeException fme =
        new FailedMergeException(failedMergeBranches, currentRevision, "${branch} ${revision}");
    String branch1String = "branch1 " + currentRevision;
    String branch2String = "branch2 " + currentRevision;
    String branch3String = "branch3 " + currentRevision;
    List<String> expectedStrings = Arrays.asList(branch1String, branch2String, branch3String);
    assertThat(fme.getDisplayString()).isEqualTo(Joiner.on("\n").join(expectedStrings));
  }

  @Test
  public void customConflictMessageWithConflictSubstitutionTest() throws Exception {
    FailedMergeException fme =
        new FailedMergeException(failedMergeBranches, currentRevision, "${branch}: ${conflict}");
    String branch1String = "branch1: branch1 merge conflict";
    String branch2String = "branch2: branch2 merge conflict";
    String branch3String = "branch3: branch3 merge conflict";
    List<String> expectedStrings = Arrays.asList(branch1String, branch2String, branch3String);
    assertThat(fme.getDisplayString()).isEqualTo(Joiner.on("\n").join(expectedStrings));
  }
}
