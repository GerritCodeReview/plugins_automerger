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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.MergePatchSetInput;
import com.google.gerrit.extensions.common.RevisionInfo;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DownstreamCreatorTest {
  private final int changeId = 1234;
  private final String changeProject = "testproject";
  private final String changeTopic = "testtopic";
  private final String changeSubject = "testmessage";

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private GerritApi gApiMock;

  private DownstreamCreator ds;

  @Before
  public void setUp() throws Exception {
    ds = new DownstreamCreator(gApiMock, Mockito.mock(ConfigLoader.class));
  }

  private List<ChangeInfo> mockChangeInfoList(String upstreamBranch) {
    return ImmutableList.of(
        mockChangeInfo(upstreamBranch, 1),
        mockChangeInfo("testwhee", 2),
        mockChangeInfo(upstreamBranch, 3));
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

    RevisionInfo revisionInfoMock = Mockito.mock(RevisionInfo.class);
    CommitInfo commit = Mockito.mock(CommitInfo.class);
    commit.parents = ImmutableList.of(parent1, parent2);
    revisionInfoMock.commit = commit;

    info.revisions.put(info.currentRevision, revisionInfoMock);
    return info;
  }

  @Test
  public void testCreateDownstreamMerge() throws Exception {
    String currentRevision = "testCurrentRevision";

    ChangeInfo changeInfoMock = Mockito.mock(ChangeInfo.class);
    changeInfoMock.id = "testnewchangeid";
    ChangeApi changeApiMock = Mockito.mock(ChangeApi.class);
    Mockito.when(changeApiMock.get(EnumSet.of(ListChangesOption.CURRENT_REVISION)))
        .thenReturn(changeInfoMock);
    Mockito.when(gApiMock.changes().create(Mockito.any(ChangeInput.class)))
        .thenReturn(changeApiMock);
    RevisionApi revisionApiMock = Mockito.mock(RevisionApi.class);
    Mockito.when(gApiMock.changes().id(Mockito.anyString()).revision(Mockito.anyString()))
        .thenReturn(revisionApiMock);

    SingleDownstreamMergeInput dsMergeInput = new SingleDownstreamMergeInput();
    dsMergeInput.currentRevision = currentRevision;
    dsMergeInput.sourceId = changeId;
    dsMergeInput.project = changeProject;
    dsMergeInput.topic = changeTopic;
    dsMergeInput.subject = changeSubject;
    dsMergeInput.downstreamBranch = "testds";
    dsMergeInput.doMerge = true;

    ds.createSingleDownstreamMerge(dsMergeInput);

    // Check ChangeInput is the right project, branch, topic, subject
    ArgumentCaptor<ChangeInput> changeInputCaptor = ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(gApiMock.changes()).create(changeInputCaptor.capture());
    ChangeInput changeInput = changeInputCaptor.getValue();
    assertThat(changeInput.project).isEqualTo(changeProject);
    assertThat(changeInput.branch).isEqualTo("testds");
    assertThat(changeInput.topic).isEqualTo(changeTopic);
    assertThat(changeInput.merge.source).isEqualTo(currentRevision);
    assertThat(changeInput.merge.strategy).isEqualTo("recursive");

    String expectedSubject = changeSubject + " am: " + currentRevision.substring(0, 10);
    assertThat(expectedSubject).isEqualTo(changeInput.subject);
  }

  @Test
  public void testCreateDownstreamMerge_skipMerge() throws Exception {
    String currentRevision = "testCurrentRevision";

    ChangeInfo changeInfoMock = Mockito.mock(ChangeInfo.class);
    changeInfoMock.id = "testnewchangeid";
    ChangeApi changeApiMock = Mockito.mock(ChangeApi.class);
    Mockito.when(changeApiMock.get(EnumSet.of(ListChangesOption.CURRENT_REVISION)))
        .thenReturn(changeInfoMock);
    Mockito.when(gApiMock.changes().create(Mockito.any(ChangeInput.class)))
        .thenReturn(changeApiMock);
    RevisionApi revisionApiMock = Mockito.mock(RevisionApi.class);
    Mockito.when(gApiMock.changes().id(Mockito.anyString()).revision(Mockito.anyString()))
        .thenReturn(revisionApiMock);

    SingleDownstreamMergeInput dsMergeInput = new SingleDownstreamMergeInput();
    dsMergeInput.currentRevision = currentRevision;
    dsMergeInput.sourceId = changeId;
    dsMergeInput.project = changeProject;
    dsMergeInput.topic = changeTopic;
    dsMergeInput.subject = changeSubject;
    dsMergeInput.downstreamBranch = "testds";
    dsMergeInput.doMerge = false;

    ds.createSingleDownstreamMerge(dsMergeInput);

    // Check ChangeInput is the right project, branch, topic, subject
    ArgumentCaptor<ChangeInput> changeInputCaptor = ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(gApiMock.changes()).create(changeInputCaptor.capture());
    ChangeInput changeInput = changeInputCaptor.getValue();
    assertThat(changeInput.project).isEqualTo(changeProject);
    assertThat(changeInput.branch).isEqualTo("testds");
    assertThat(changeInput.topic).isEqualTo(changeTopic);
    assertThat(changeInput.merge.source).isEqualTo(currentRevision);

    // Check that it was actually skipped
    String expectedSubject = changeSubject + " skipped: " + currentRevision.substring(0, 10);
    assertThat(changeInput.merge.strategy).isEqualTo("ours");
    assertThat(expectedSubject).isEqualTo(changeInput.subject);
  }

  @Test
  public void testCreateDownstreamMerges() throws Exception {
    Map<String, Boolean> downstreamBranchMap = new HashMap<>();
    downstreamBranchMap.put("testone", true);
    downstreamBranchMap.put("testtwo", true);

    MultipleDownstreamMergeInput mdsMergeInput = new MultipleDownstreamMergeInput();
    mdsMergeInput.dsBranchMap = downstreamBranchMap;
    mdsMergeInput.sourceId = changeId;
    mdsMergeInput.project = changeProject;
    mdsMergeInput.topic = changeTopic;
    mdsMergeInput.subject = changeSubject;
    mdsMergeInput.obsoleteRevision = null;
    mdsMergeInput.currentRevision = "testCurrent";

    ds.createDownstreamMerges(mdsMergeInput);

    ArgumentCaptor<ChangeInput> changeInputCaptor = ArgumentCaptor.forClass(ChangeInput.class);
    Mockito.verify(gApiMock.changes(), Mockito.times(2)).create(changeInputCaptor.capture());
    List<String> capturedBranches =
        Lists.transform(
            changeInputCaptor.getAllValues(),
            new Function<ChangeInput, String>() {
              @Override
              public String apply(ChangeInput c) {
                return c.branch;
              }
            });
    assertThat(capturedBranches).containsExactly("testone", "testtwo");
  }

  @Test
  public void testCreateDownstreamMerges_withPreviousRevisions() throws Exception {
    Map<String, Boolean> downstreamBranchMap = new HashMap<>();
    downstreamBranchMap.put("testone", true);
    downstreamBranchMap.put("testtwo", true);

    List<ChangeInfo> changeInfoList = mockChangeInfoList("testup");
    Mockito.when(
            gApiMock
                .changes()
                .query(Mockito.anyString())
                .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
                .get())
        .thenReturn(changeInfoList);

    MultipleDownstreamMergeInput mdsMergeInput = new MultipleDownstreamMergeInput();
    mdsMergeInput.dsBranchMap = downstreamBranchMap;
    mdsMergeInput.sourceId = changeId;
    mdsMergeInput.project = changeProject;
    mdsMergeInput.topic = changeTopic;
    mdsMergeInput.subject = changeSubject;
    mdsMergeInput.obsoleteRevision = "testup";
    mdsMergeInput.currentRevision = "testCurrent";

    ds.createDownstreamMerges(mdsMergeInput);

    // Check that previous revisions were updated
    Mockito.verify(gApiMock.changes().id(Mockito.anyInt()), Mockito.times(2))
        .createMergePatchSet(Mockito.any(MergePatchSetInput.class));
  }

  @Test
  public void testGetExistingMergesOnBranch() throws Exception {
    List<ChangeInfo> changeInfoList = mockChangeInfoList("testup");
    Mockito.when(
            gApiMock
                .changes()
                .query(Mockito.anyString())
                .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
                .get())
        .thenReturn(changeInfoList);

    List<Integer> downstreamChangeNumbers =
        ds.getExistingMergesOnBranch("testup", "testtopic", "testdown");
    assertThat(downstreamChangeNumbers).containsExactly(1, 3).inOrder();
  }
}
