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

import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;


public class DownstreamCreatorIT {
  private GerritApi gApiMock;
  private DownstreamCreator ds;
  private ConfigLoader config;
  private final String changeId = "testid";
  private final String changeProject = "testproject";
  private final String changeBranch = "testbranch";
  private final String changeTopic = "testtopic";
  private final String changeSubject = "testmessage";

  @Before
  public void setUp() throws Exception {
    gApiMock = Mockito.mock(GerritApi.class, Mockito.RETURNS_DEEP_STUBS);
    config = new ConfigLoader(gApiMock);
    ds = new DownstreamCreator(gApiMock, config);
  }

  private List<ChangeInfo> mockChangeInfoList(String upstreamBranch) {
    List<ChangeInfo> changeInfoList = new ArrayList<>();
    changeInfoList.add(mockChangeInfo(upstreamBranch, 1));
    changeInfoList.add(mockChangeInfo("testwhee", 2));
    changeInfoList.add(mockChangeInfo(upstreamBranch, 3));
    return changeInfoList;
  }

  private ChangeInfo mockChangeInfo(String upstreamRevision, int number) {
    List<CommitInfo> parents = new ArrayList<>();
    CommitInfo parent1 = Mockito.mock(CommitInfo.class);
    parent1.commit = "infoparent" + number;
    CommitInfo parent2 = Mockito.mock(CommitInfo.class);
    parent2.commit = upstreamRevision;
    parents.add(parent1);
    parents.add(parent2);

    ChangeInfo info = Mockito.mock(ChangeInfo.class);
    info._number = number;
    info.currentRevision = "info" + number;
    info.revisions = Mockito.mock(Map.class);

    RevisionInfo revisionInfoMock = Mockito.mock(RevisionInfo.class);
    CommitInfo commit = Mockito.mock(CommitInfo.class);
    commit.parents = parents;
    revisionInfoMock.commit = commit;

    Mockito.when(info.revisions.get(info.currentRevision))
        .thenReturn(revisionInfoMock);

    return info;
  }

  @Test
  public void testCreateDownstreamMerge() throws Exception {
    String currentRevision = "testCurrentRevision";

    ChangeInfo changeInfoMock = Mockito.mock(ChangeInfo.class);
    changeInfoMock.id = "testnewchangeid";
    ChangeApi changeApiMock = Mockito.mock(ChangeApi.class);
    Mockito.when(changeApiMock.get(
        EnumSet.of(ListChangesOption.CURRENT_REVISION)))
        .thenReturn(changeInfoMock);
    Mockito.when(gApiMock.changes().create(Mockito.any(ChangeInput.class)))
        .thenReturn(changeApiMock);
    RevisionApi revisionApiMock = Mockito.mock(RevisionApi.class);
    Mockito.when(
        gApiMock.changes().id(Mockito.anyString())
            .revision(Mockito.anyString()))
        .thenReturn(revisionApiMock);
    ds.createDownstreamMerge(
        currentRevision, changeId, changeProject, changeTopic,
        changeSubject, "testds", true);

    // Check ReviewInput is +2
    ArgumentCaptor<ReviewInput> reviewInputArgument =
        ArgumentCaptor.forClass(ReviewInput.class);
    Mockito.verify(revisionApiMock).review(reviewInputArgument.capture());
    ReviewInput reviewInput = reviewInputArgument.getValue();
    assertThat(reviewInput.labels.get("Code-Review")).isEqualTo(2);

    // Check ChangeInput is the right project, branch, topic, subject
    ArgumentCaptor<ChangeInput> changeInputCaptor =
        ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(
        gApiMock.changes()).create(changeInputCaptor.capture());
    ChangeInput changeInput = changeInputCaptor.getValue();
    assertThat(changeProject).isEqualTo(changeInput.project);
    assertThat("testds").isEqualTo(changeInput.branch);
    assertThat(changeTopic).isEqualTo(changeInput.topic);
    assertThat(changeInput.merge.source).isEqualTo(currentRevision);

    String expectedSubject = changeSubject + " am: " +
                                 currentRevision.substring(0, 10);
    assertThat(expectedSubject).isEqualTo(changeInput.subject);
  }

  @Test
  public void testCreateDownstreamMerge_skipMerge() throws Exception {
    String currentRevision = "testCurrentRevision";

    ChangeInfo changeInfoMock = Mockito.mock(ChangeInfo.class);
    changeInfoMock.id = "testnewchangeid";
    ChangeApi changeApiMock = Mockito.mock(ChangeApi.class);
    Mockito.when(changeApiMock.get(
        EnumSet.of(ListChangesOption.CURRENT_REVISION)))
        .thenReturn(changeInfoMock);
    Mockito.when(gApiMock.changes().create(Mockito.any(ChangeInput.class)))
        .thenReturn(changeApiMock);
    RevisionApi revisionApiMock = Mockito.mock(RevisionApi.class);
    Mockito.when(
        gApiMock.changes().id(Mockito.anyString())
            .revision(Mockito.anyString()))
        .thenReturn(revisionApiMock);
    ds.createDownstreamMerge(
        currentRevision, changeId, changeProject, changeTopic,
        changeSubject, "testds", false);

    // Check ReviewInput is +2
    ArgumentCaptor<ReviewInput> reviewInputArgument =
        ArgumentCaptor.forClass(ReviewInput.class);
    Mockito.verify(revisionApiMock).review(reviewInputArgument.capture());
    ReviewInput reviewInput = reviewInputArgument.getValue();
    assertThat(reviewInput.labels.get("Code-Review")).isEqualTo(2);

    // Check ChangeInput is the right project, branch, topic, subject
    ArgumentCaptor<ChangeInput> changeInputCaptor =
        ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(
        gApiMock.changes()).create(changeInputCaptor.capture());
    ChangeInput changeInput = changeInputCaptor.getValue();
    assertThat(changeProject).isEqualTo(changeInput.project);
    assertThat("testds").isEqualTo(changeInput.branch);
    assertThat(changeTopic).isEqualTo(changeInput.topic);
    assertThat(changeInput.merge.source).isEqualTo(currentRevision);

    // Check that it was actually skipped
    String expectedSubject =
        changeSubject + " am: " + currentRevision.substring(0, 10) +
            " [skipped]";
    assertThat(changeInput.merge.strategy).isEqualTo("ours");
    assertThat(expectedSubject)
        .isEqualTo(changeInput.subject);
  }

  @Test
  public void testCreateDownstreamMerges() throws Exception {
    Map<String, Boolean> downstreamBranchMap = new HashMap<String, Boolean>();
    downstreamBranchMap.put("testone", true);
    downstreamBranchMap.put("testtwo", true);
    ds.createDownstreamMerges(
        downstreamBranchMap, changeId, changeProject, changeTopic,
        changeSubject, null, "testCurrent");

    ArgumentCaptor<ChangeInput> changeInputCaptor =
        ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(gApiMock.changes(), Mockito.times(2))
        .create(changeInputCaptor.capture());
    List<ChangeInput> capturedChangeInputs = changeInputCaptor.getAllValues();
    assertThat(capturedChangeInputs.get(0).branch).isEqualTo("testone");
    assertThat(capturedChangeInputs.get(1).branch).isEqualTo("testtwo");
  }

  @Test
  public void testCreateDownstreamMerges_withPreviousRevisions()
      throws Exception {
    Map<String, Boolean> downstreamBranchMap = new HashMap<String, Boolean>();
    downstreamBranchMap.put("testone", true);
    downstreamBranchMap.put("testtwo", true);

    List<ChangeInfo> changeInfoList = mockChangeInfoList("testup");
    Mockito.when(
        gApiMock.changes().query(Mockito.anyString())
            .withOptions(ListChangesOption.ALL_REVISIONS,
                ListChangesOption.CURRENT_COMMIT).get())
        .thenReturn(changeInfoList);

    ds.createDownstreamMerges(
        downstreamBranchMap, changeId, changeProject, changeTopic,
        changeSubject, "testup", "testCurrent");

    // Check that previous revisions were abandoned
    Mockito.verify(gApiMock.changes().id(Mockito.anyInt()), Mockito.times(2))
        .abandon(Mockito.any(AbandonInput.class));
    ArgumentCaptor<ChangeInput> changeInputCaptor =
        ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(gApiMock.changes(), Mockito.times(2))
        .create(changeInputCaptor.capture());

    List<ChangeInput> capturedChangeInputs = changeInputCaptor.getAllValues();
    assertThat(capturedChangeInputs.get(0).branch).isEqualTo("testone");
    assertThat(capturedChangeInputs.get(1).branch).isEqualTo("testtwo");
  }

  @Test
  public void testGetExistingDownstreamMerges() throws Exception {
    List<ChangeInfo> changeInfoList = mockChangeInfoList("testup");
    Mockito.when(
        gApiMock.changes().query(Mockito.anyString())
            .withOptions(ListChangesOption.ALL_REVISIONS,
                ListChangesOption.CURRENT_COMMIT).get())
        .thenReturn(changeInfoList);

    List<Integer> downstreamChangeNumbers =
        ds.getExistingDownstreamMerges("testup", "testtopic", "testdown");
    List<Integer> expectedChangeNumbers = new ArrayList<Integer>();
    expectedChangeNumbers.add(1);
    expectedChangeNumbers.add(3);
    assertThat(downstreamChangeNumbers).isEqualTo(expectedChangeNumbers);
  }
}
