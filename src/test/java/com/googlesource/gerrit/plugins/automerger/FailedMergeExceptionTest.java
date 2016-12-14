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

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

public class FailedMergeExceptionTest {
  private FailedMergeException fme;
  private Map<String, String> failedMerges;
  private String extraMessage;

  @Before
  public void setUp() throws Exception {
    failedMerges = new HashMap<>();
    failedMerges.put("dsbranch", "merge conflict message goes here");
    extraMessage = "extra message goes here";
    fme = new FailedMergeException(failedMerges, extraMessage);
  }

  @Test
  public void testFailedMergeException_displayConflicts() {
    String expectedMessage =
        "Merge conflict found on dsbranch. extra message goes here\n"
            + "\ndsbranch:\nmerge conflict message goes here";
    System.out.println(fme.displayConflicts());
    System.out.println(expectedMessage);
    assertThat(fme.displayConflicts()).isEqualTo(expectedMessage);
  }
}
