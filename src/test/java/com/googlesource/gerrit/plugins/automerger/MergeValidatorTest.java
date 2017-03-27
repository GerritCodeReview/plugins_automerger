// Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MergeValidatorTest {
  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private GerritApi gApiMock;

  @Mock private ConfigLoader config;

  private MergeValidator mergeValidator;

  @Before
  public void setUp() throws Exception {
    mergeValidator = new MergeValidator(gApiMock, config);
  }

  private ChangeInfo mockChangeInfo(String upstreamRevision, int number) {
    CommitInfo parent1 = Mockito.mock(CommitInfo.class);
    parent1.commit = "infoparent" + number;
    CommitInfo parent2 = Mockito.mock(CommitInfo.class);
    parent2.commit = upstreamRevision;

    ChangeInfo info = Mockito.mock(ChangeInfo.class);
    info._number = number;
    info.currentRevision = "info" + number;
    info.revisions = new HashMap<>();
    info.branch = "master";
    info.project = "platform/whee";
    info.topic = "testtopic";

    RevisionInfo revisionInfoMock = Mockito.mock(RevisionInfo.class);
    CommitInfo commit = Mockito.mock(CommitInfo.class);
    commit.parents = ImmutableList.of(parent1, parent2);
    revisionInfoMock.commit = commit;

    info.revisions.put(info.currentRevision, revisionInfoMock);
    return info;
  }

  @Test
  public void testGetMissingDownstreamMerges() throws Exception {
    ChangeInfo upstreamChangeInfo = mockChangeInfo("d3adb33f", 1);
    Set<String> downstreamBranches = new HashSet<>();
    downstreamBranches.add("someDownstreamBranch");
    Mockito.when(
            config.getDownstreamBranches(upstreamChangeInfo.branch, upstreamChangeInfo.project))
        .thenReturn(downstreamBranches);
    String queryString = "topic:testtopic status:open branch:someDownstreamBranch";
    List<ChangeInfo> changes = new ArrayList<>();
    changes.add(mockChangeInfo(upstreamChangeInfo.currentRevision, 2));
    Mockito.when(
            gApiMock
                .changes()
                .query(queryString)
                .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
                .get())
        .thenReturn(changes);

    assertThat(mergeValidator.getMissingDownstreamMerges(upstreamChangeInfo))
        .isEqualTo(Collections.emptySet());
  }

  @Test
  public void testGetMissingDownstreamMerges_missing() throws Exception {
    ChangeInfo upstreamChangeInfo = mockChangeInfo("d3adb33f", 1);
    Set<String> downstreamBranches = new HashSet<>();
    downstreamBranches.add("someDownstreamBranch");
    downstreamBranches.add("anotherDownstreamBranch");
    Mockito.when(
            config.getDownstreamBranches(upstreamChangeInfo.branch, upstreamChangeInfo.project))
        .thenReturn(downstreamBranches);
    String queryString = "topic:testtopic status:open branch:someDownstreamBranch";
    List<ChangeInfo> changes = new ArrayList<>();
    changes.add(mockChangeInfo(upstreamChangeInfo.currentRevision, 2));
    Mockito.when(
            gApiMock
                .changes()
                .query(queryString)
                .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
                .get())
        .thenReturn(changes);

    Set<String> expectedMissing = new HashSet<>();
    expectedMissing.add("anotherDownstreamBranch");
    assertThat(mergeValidator.getMissingDownstreamMerges(upstreamChangeInfo))
        .isEqualTo(expectedMissing);
  }
}
