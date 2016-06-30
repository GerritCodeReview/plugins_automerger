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

import static org.easymock.EasyMock.*;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Charsets;
import com.google.common.base.Supplier;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.gerrit.acceptance.PluginDaemonTest;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.DraftPublishedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;

import org.junit.Before;
import org.junit.Test;

import org.easymock.EasyMock;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class DownstreamCreatorIT extends PluginDaemonTest {
    GerritApi gApiMock;
    Changes changesMock;
    ChangeApi changeApiMock;
    RevisionApi revisionApiMock;
    DownstreamCreator ds;
    ConfigLoader config;
    Changes.QueryRequest queryMock;
    ChangeAttribute changeAttributeMock;

    @Before
    public void setUp() throws Exception {
        gApiMock = createMock(GerritApi.class);
        changesMock = createMock(Changes.class);
        changeApiMock = createMock(ChangeApi.class);
        revisionApiMock = createMock(RevisionApi.class);
        queryMock = createMock(Changes.QueryRequest.class);
        config = createMock(ConfigLoader.class);
        ds = new DownstreamCreator(gApiMock, config);

        changeAttributeMock = createMock(ChangeAttribute.class);
        changeAttributeMock.project = "testproject";
        changeAttributeMock.branch = "testbranch";
        changeAttributeMock.topic = "testtopic";
        changeAttributeMock.commitMessage = "testmessage";
    }

    private List<ChangeInfo> mockChangeInfoList(String upstreamRevision)
        throws Exception {
        String firstChangeRevision = "firstchangerev";
        String secondChangeRevision = "secondchangerev";
        String thirdChangeRevision = "thirdchangerev";
        CommitInfo secondParentMock = createMock(CommitInfo.class);
        secondParentMock.commit = upstreamRevision;
        EasyMock.replay(secondParentMock);

        // single parent revision info
        List<CommitInfo> singleParentList = new ArrayList<CommitInfo>();
        singleParentList.add(createMock(CommitInfo.class));
        CommitInfo singleParentCommitInfo = createMock(CommitInfo.class);
        singleParentCommitInfo.parents = singleParentList;
        EasyMock.replay(singleParentCommitInfo);
        RevisionInfo singleParentRevisionInfo = createMock(RevisionInfo.class);
        singleParentRevisionInfo.commit = singleParentCommitInfo;
        EasyMock.replay(singleParentRevisionInfo);

        // dual parent revision info
        List<CommitInfo> dualParentList = new ArrayList<CommitInfo>();
        dualParentList.add(createMock(CommitInfo.class));
        dualParentList.add(secondParentMock);
        CommitInfo dualParentCommitInfo = createMock(CommitInfo.class);
        EasyMock.replay(dualParentCommitInfo);
        dualParentCommitInfo.parents = dualParentList;
        RevisionInfo dualParentRevisionInfo = createMock(RevisionInfo.class);
        dualParentRevisionInfo.commit = dualParentCommitInfo;
        EasyMock.replay(dualParentRevisionInfo);

        // Set up revision map for first change
        Map<String, RevisionInfo> firstChangeRevisionsMap =
            createMock(Map.class);
        expect(firstChangeRevisionsMap.get(firstChangeRevision))
            .andStubReturn(dualParentRevisionInfo);
        EasyMock.replay(firstChangeRevisionsMap);
        // Set up revision map for second change
        Map<String, RevisionInfo> secondChangeRevisionsMap =
            createMock(Map.class);
        expect(secondChangeRevisionsMap.get(secondChangeRevision))
            .andStubReturn(singleParentRevisionInfo);
        EasyMock.replay(secondChangeRevisionsMap);
        // Set up revision map for third change
        Map<String, RevisionInfo> thirdChangeRevisionsMap =
            createMock(Map.class);
        expect(thirdChangeRevisionsMap.get(thirdChangeRevision))
            .andStubReturn(dualParentRevisionInfo);
        EasyMock.replay(thirdChangeRevisionsMap);

        // Initialize first change
        ChangeInfo firstChange = new ChangeInfo();
        firstChange._number = 1;
        firstChange.currentRevision = firstChangeRevision;
        firstChange.revisions = firstChangeRevisionsMap;
        // Initialize second change
        ChangeInfo secondChange = new ChangeInfo();
        secondChange._number = 2;
        secondChange.currentRevision = secondChangeRevision;
        secondChange.revisions = secondChangeRevisionsMap;
        // Initialize third change
        ChangeInfo thirdChange = new ChangeInfo();
        thirdChange._number = 3;
        thirdChange.currentRevision = thirdChangeRevision;
        thirdChange.revisions = thirdChangeRevisionsMap;

        List<ChangeInfo> changeInfoList = new ArrayList<ChangeInfo>();
        changeInfoList.add(firstChange);
        changeInfoList.add(secondChange);
        changeInfoList.add(thirdChange);
        return changeInfoList;
    }

    @Test
    public void testCreateDownstreamMerge() throws Exception {
        expect(config.getCodeReviewLabel()).andReturn("Code-Review");
        EasyMock.replay(config);

        ChangeInfo fakeChangeInfo = new ChangeInfo();
        fakeChangeInfo.id = "whee~project~Iabsdfbsdabs";
        fakeChangeInfo.currentRevision = "asdffakerev";

        revisionApiMock.review(EasyMock.anyObject(ReviewInput.class));
        expectLastCall().once();

        expect(changeApiMock
                   .get(EnumSet.of(ListChangesOption.CURRENT_REVISION)))
            .andStubReturn(fakeChangeInfo);
        expect(changeApiMock.revision(EasyMock.anyObject(String.class)))
            .andStubReturn(revisionApiMock);

        expect(changesMock.create(EasyMock.anyObject(ChangeInput.class)))
            .andReturn(changeApiMock);
        expect(changesMock.id(EasyMock.anyObject(String.class)))
            .andStubReturn(changeApiMock);
        expect(gApiMock.changes()).andStubReturn(changesMock);
        EasyMock.replay(revisionApiMock);
        EasyMock.replay(changeApiMock);
        EasyMock.replay(changesMock);
        EasyMock.replay(gApiMock);
        ds.createDownstreamMerge(
            "testCurrentRevision", changeAttributeMock, "testds", true);
        EasyMock.verify(changesMock);
    }

    @Test
    public void testCreateDownstreamMerges() throws Exception {
        ChangeInfo fakeChangeInfo = new ChangeInfo();
        fakeChangeInfo.id = "whee~project~Iabsdfbsdabs";
        fakeChangeInfo.currentRevision = "asdffakerev";

        revisionApiMock.review(EasyMock.anyObject(ReviewInput.class));
        expectLastCall().times(2);
        EasyMock.replay(revisionApiMock);
        expect(changeApiMock
                   .get(EnumSet.of(ListChangesOption.CURRENT_REVISION)))
            .andStubReturn(fakeChangeInfo);
        expect(changeApiMock.revision(EasyMock.anyObject(String.class)))
            .andStubReturn(revisionApiMock);
        EasyMock.replay(changeApiMock);

        Map<String, Boolean> downstreamBranchMap =
            new HashMap<String, Boolean>();
        downstreamBranchMap.put("testone", true);
        downstreamBranchMap.put("testtwo", true);

        expect(config.isSkipMerge(changeAttributeMock.branch,
            "testone", changeAttributeMock.commitMessage)).andReturn(false);
        expect(config.isSkipMerge(changeAttributeMock.branch,
            "testtwo", changeAttributeMock.commitMessage)).andReturn(false);
        expect(config.getCodeReviewLabel()).andStubReturn("Code-Review");
        EasyMock.replay(config);

        expect(changesMock.create(EasyMock.anyObject(ChangeInput.class)))
            .andReturn(changeApiMock).times(2);
        expect(changesMock.id(EasyMock.anyObject(String.class)))
            .andStubReturn(changeApiMock);
        EasyMock.replay(changesMock);
        expect(gApiMock.changes()).andStubReturn(changesMock);
        EasyMock.replay(gApiMock);

        ds.createDownstreamMerges(
            downstreamBranchMap, changeAttributeMock, null, "testCurrent");
        EasyMock.verify(changesMock);
    }

    @Test
    public void testCreateDownstreamMerges_withPreviousRevisions()
        throws Exception {
        ChangeInfo fakeChangeInfo = new ChangeInfo();
        fakeChangeInfo.id = "whee~project~Iabsdfbsdabs";
        fakeChangeInfo.currentRevision = "asdffakerev";

        revisionApiMock.review(EasyMock.anyObject(ReviewInput.class));
        expectLastCall().times(2);
        expect(changeApiMock
                   .get(EnumSet.of(ListChangesOption.CURRENT_REVISION)))
            .andStubReturn(fakeChangeInfo);
        expect(changeApiMock.revision(EasyMock.anyObject(String.class)))
            .andStubReturn(revisionApiMock);

        List<ChangeInfo> changeInfoList = mockChangeInfoList("testupstream");

        Map<String, Boolean> downstreamBranchMap =
            new HashMap<String, Boolean>();
        downstreamBranchMap.put("testone", true);
        downstreamBranchMap.put("testtwo", true);

        expect(config.isSkipMerge(changeAttributeMock.branch,
            "testone", changeAttributeMock.commitMessage)).andReturn(false);
        expect(config.isSkipMerge(changeAttributeMock.branch,
            "testtwo", changeAttributeMock.commitMessage)).andReturn(false);
        expect(config.getCodeReviewLabel()).andStubReturn("Code-Review");
        EasyMock.replay(config);

        expect(gApiMock.changes()).andStubReturn(changesMock);
        expect(changesMock.id(EasyMock.anyInt())).andStubReturn(changeApiMock);
        expect(changesMock.id(EasyMock.anyObject(String.class)))
            .andStubReturn(changeApiMock);
        expect(changesMock.query(EasyMock.anyObject(String.class)))
            .andStubReturn(queryMock);
        expect(changesMock.create(EasyMock.anyObject(ChangeInput.class)))
            .andReturn(changeApiMock).times(2);
        expect(queryMock.withOptions(ListChangesOption.ALL_REVISIONS,
            ListChangesOption.CURRENT_COMMIT)).andStubReturn(queryMock);
        expect(queryMock.get()).andStubReturn(changeInfoList);

        // Assert that we abandon the previous four revisions
        // Two downstream merges for each downstream branch
        changeApiMock.abandon(EasyMock.anyObject(AbandonInput.class));
        expectLastCall().times(4);
        EasyMock.replay(revisionApiMock);
        EasyMock.replay(changeApiMock);
        EasyMock.replay(queryMock);
        EasyMock.replay(changesMock);
        EasyMock.replay(gApiMock);

        ds.createDownstreamMerges(
            downstreamBranchMap, changeAttributeMock, "testupstream",
            "testCurrent");
        EasyMock.verify(changeApiMock);
        EasyMock.verify(changesMock);
    }

    @Test
    public void testGetExistingDownstreamMerges() throws Exception {
        List<ChangeInfo> changeInfoList = mockChangeInfoList("testupstream");
        expect(gApiMock.changes()).andStubReturn(changesMock);
        expect(changesMock.query(EasyMock.anyObject(String.class)))
            .andStubReturn(queryMock);
        expect(queryMock.withOptions(ListChangesOption.ALL_REVISIONS,
            ListChangesOption.CURRENT_COMMIT)).andStubReturn(queryMock);
        expect(queryMock.get()).andStubReturn(changeInfoList);

        EasyMock.replay(queryMock);
        EasyMock.replay(changesMock);
        EasyMock.replay(gApiMock);

        List<Integer> downstreamChangeNumbers =
            ds.getExistingDownstreamMerges(
                "testupstream", "testopic", "testdownstream");
        List<Integer> expectedChangeNumbers = new ArrayList<Integer>();
        expectedChangeNumbers.add(1);
        expectedChangeNumbers.add(3);
        assertThat(downstreamChangeNumbers).isEqualTo(expectedChangeNumbers);
    }
}
