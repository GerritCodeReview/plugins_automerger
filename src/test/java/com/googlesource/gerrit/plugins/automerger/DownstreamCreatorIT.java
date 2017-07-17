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

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.testutil.TestTimeUtil;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

@TestPlugin(
  name = "automerger",
  sysModule = "com.googlesource.gerrit.plugins.automerger.AutomergerModule"
)
public class DownstreamCreatorIT extends LightweightPluginDaemonTest {
  @Test
  public void testExpectedFlow() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(3);
    // +2 and submit
    merge(result);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    ChangeApi dsOneChange = gApi.changes().id(dsOneChangeInfo._number);
    assertThat(getVote(dsOneChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(dsOneChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    ChangeApi dsTwoChange = gApi.changes().id(dsTwoChangeInfo._number);
    assertThat(getVote(dsTwoChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(dsTwoChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    ChangeApi masterChange = gApi.changes().id(masterChangeInfo._number);
    assertThat(getVote(masterChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(masterChange, "Code-Review").tag).isEqualTo(null);
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
  }

  @Test
  public void testDiamondMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result initialResult = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = initialResult.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "left"));
    createBranch(new Branch.NameKey(projectName, "right"));
    initialResult.assertOkStatus();
    merge(initialResult);
    // Reset to create a sibling
    ObjectId initial = repo().exactRef("HEAD").getLeaf().getObjectId();
    testRepo.reset(initial);
    // Make left != right
    PushOneCommit.Result left =
        createChange(
            testRepo, "left", "subject", "filename", "echo \"Hello asdfsd World\"", "randtopic");
    left.assertOkStatus();
    merge(left);

    String leftRevision = gApi.projects().name(projectName).branch("left").get().revision;
    String rightRevision = gApi.projects().name(projectName).branch("right").get().revision;
    // For this test, right != left
    assertThat(leftRevision).isNotEqualTo(rightRevision);
    createBranch(new Branch.NameKey(projectName, "bottom"));
    pushDiamondConfig(manifestNameKey.get(), projectName);
    // After we upload our config, we upload a new patchset to create the downstreams
    PushOneCommit.Result result = createChange("subject", "filename2", "echo Hello", "sometopic");
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(5);
    // +2 and submit
    merge(result);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Should create two changes on bottom, since left and right are different.
    ChangeInfo bottomChangeInfoA = sortedChanges.get(0);
    assertThat(bottomChangeInfoA.branch).isEqualTo("bottom");
    ChangeApi bottomChangeA = gApi.changes().id(bottomChangeInfoA._number);
    assertThat(getVote(bottomChangeA, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(bottomChangeA, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo bottomChangeInfoB = sortedChanges.get(1);
    assertThat(bottomChangeInfoB.branch).isEqualTo("bottom");
    ChangeApi bottomChangeB = gApi.changes().id(bottomChangeInfoB._number);
    assertThat(getVote(bottomChangeB, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(bottomChangeB, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo leftChangeInfo = sortedChanges.get(2);
    assertThat(leftChangeInfo.branch).isEqualTo("left");
    ChangeApi leftChange = gApi.changes().id(leftChangeInfo._number);
    assertThat(getVote(leftChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(leftChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo masterChangeInfo = sortedChanges.get(3);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    ChangeApi masterChange = gApi.changes().id(masterChangeInfo._number);
    assertThat(getVote(masterChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(masterChange, "Code-Review").tag).isEqualTo(null);

    ChangeInfo rightChangeInfo = sortedChanges.get(4);
    assertThat(rightChangeInfo.branch).isEqualTo("right");
    ChangeApi rightChange = gApi.changes().id(rightChangeInfo._number);
    assertThat(getVote(rightChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(rightChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    String shortLeftSha = leftChangeInfo.currentRevision.substring(0, 10);
    String shortRightSha = rightChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(leftChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
    assertThat(rightChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
    assertThat(bottomChangeInfoA.subject)
        .isEqualTo(
            "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortLeftSha);
    assertThat(bottomChangeInfoB.subject)
        .isEqualTo(
            "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortRightSha);
  }

  @Test
  public void testDiamondMerge_identicalDiamondSides() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result initialResult = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = initialResult.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "left"));
    createBranch(new Branch.NameKey(projectName, "right"));
    initialResult.assertOkStatus();
    merge(initialResult);

    String leftRevision = gApi.projects().name(projectName).branch("left").get().revision;
    String rightRevision = gApi.projects().name(projectName).branch("right").get().revision;
    // For this test, right == left
    assertThat(leftRevision).isEqualTo(rightRevision);
    createBranch(new Branch.NameKey(projectName, "bottom"));
    pushDiamondConfig(manifestNameKey.get(), projectName);

    // Freeze time so that the merge commit from left->bottom and right->bottom have same SHA
    TestTimeUtil.resetWithClockStep(0, TimeUnit.MILLISECONDS);
    TestTimeUtil.setClock(new Timestamp(TestTimeUtil.START.getMillis()));
    // After we upload our config, we upload a new patchset to create the downstreams.
    PushOneCommit.Result result = createChange("subject", "filename2", "echo Hello", "sometopic");
    TestTimeUtil.useSystemTime();
    result.assertOkStatus();

    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(4);
    // +2 and submit
    merge(result);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Should create two changes on bottom, since left and right are different.
    ChangeInfo bottomChangeInfo = sortedChanges.get(0);
    assertThat(bottomChangeInfo.branch).isEqualTo("bottom");
    ChangeApi bottomChange = gApi.changes().id(bottomChangeInfo._number);
    assertThat(getVote(bottomChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(bottomChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo leftChangeInfo = sortedChanges.get(1);
    assertThat(leftChangeInfo.branch).isEqualTo("left");
    ChangeApi leftChange = gApi.changes().id(leftChangeInfo._number);
    assertThat(getVote(leftChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(leftChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    ChangeApi masterChange = gApi.changes().id(masterChangeInfo._number);
    assertThat(getVote(masterChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(masterChange, "Code-Review").tag).isEqualTo(null);

    ChangeInfo rightChangeInfo = sortedChanges.get(3);
    assertThat(rightChangeInfo.branch).isEqualTo("right");
    ChangeApi rightChange = gApi.changes().id(rightChangeInfo._number);
    assertThat(getVote(rightChange, "Code-Review").value).isEqualTo(2);
    assertThat(getVote(rightChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    String shortLeftSha = leftChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(leftChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
    assertThat(rightChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
    assertThat(bottomChangeInfo.subject)
        .isEqualTo(
            "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortLeftSha);
  }

  @Test
  public void testChangeStack() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    PushOneCommit.Result result2 = createChange("subject2", "filename2", "content2", "testtopic");
    result2.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
            .get();
    assertThat(changesInTopic).hasSize(6);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Change A
    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    // Change B
    ChangeInfo dsOneChangeInfo2 = sortedChanges.get(1);
    assertThat(dsOneChangeInfo2.branch).isEqualTo("ds_one");
    String dsOneChangeInfo2FirstParentSha =
        dsOneChangeInfo2
            .revisions
            .get(dsOneChangeInfo2.currentRevision)
            .commit
            .parents
            .get(0)
            .commit;
    assertThat(dsOneChangeInfo.currentRevision).isEqualTo(dsOneChangeInfo2FirstParentSha);

    // Change A'
    ChangeInfo dsTwoChangeInfo = sortedChanges.get(2);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    // Change B'
    ChangeInfo dsTwoChangeInfo2 = sortedChanges.get(3);
    assertThat(dsTwoChangeInfo2.branch).isEqualTo("ds_two");
    String dsTwoChangeInfo2FirstParentSha =
        dsTwoChangeInfo2
            .revisions
            .get(dsTwoChangeInfo2.currentRevision)
            .commit
            .parents
            .get(0)
            .commit;
    // Check that first parent of B' is A'
    assertThat(dsTwoChangeInfo.currentRevision).isEqualTo(dsTwoChangeInfo2FirstParentSha);

    // Change A''
    ChangeInfo masterChangeInfo = sortedChanges.get(4);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    // Change B''
    ChangeInfo masterChangeInfo2 = sortedChanges.get(5);
    assertThat(masterChangeInfo2.branch).isEqualTo("master");
    String masterChangeInfo2FirstParentSha =
        masterChangeInfo2
            .revisions
            .get(masterChangeInfo2.currentRevision)
            .commit
            .parents
            .get(0)
            .commit;
    // Check that first parent of B'' is A''
    assertThat(masterChangeInfo.currentRevision).isEqualTo(masterChangeInfo2FirstParentSha);

    // Ensure that commit subjects are correct
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject).isEqualTo("[automerger] test commit am: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject).isEqualTo("[automerger] test commit am: " + shortMasterSha);
  }

  @Test
  public void testBlankMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange("DO NOT MERGE subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId(), "DO NOT MERGE subject", "filename", "content");
    result.assertOkStatus();

    ChangeApi change = gApi.changes().id(result.getChangeId());
    BinaryResult content = change.current().file("filename").content();

    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + change.topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    ChangeApi dsOneChange = gApi.changes().id(dsOneChangeInfo._number);
    assertThat(getVote(dsOneChange, "Code-Review").value).isEqualTo(0);
    assertThat(getVote(dsOneChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");
    // It should skip ds_one, since this is a DO NOT MERGE
    assertThat(dsOneChange.get().subject).contains("skipped:");
    assertThat(dsOneChange.current().files().keySet()).contains("filename");
    assertThat(dsOneChange.current().files().get("filename").linesDeleted).isEqualTo(1);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    ChangeApi dsTwoChange = gApi.changes().id(dsTwoChangeInfo._number);
    assertThat(getVote(dsTwoChange, "Code-Review").value).isEqualTo(0);
    assertThat(getVote(dsTwoChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");
    // It should not skip ds_two, since it is marked with mergeAll: true
    assertThat(dsTwoChange.get().subject).doesNotContain("skipped:");
    BinaryResult dsTwoContent = dsTwoChange.current().file("filename").content();
    assertThat(dsTwoContent.asString()).isEqualTo(content.asString());

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    ChangeApi masterChange = gApi.changes().id(masterChangeInfo._number);
    assertThat(getVote(masterChange, "Code-Review").value).isEqualTo(0);
    assertThat(getVote(masterChange, "Code-Review").tag).isEqualTo(null);
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " skipped: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
  }

  @Test
  public void testAlwaysBlankMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange("DO NOT MERGE ANYWHERE subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId(), "DO NOT MERGE ANYWHERE subject", "filename", "content");
    result.assertOkStatus();

    ChangeApi change = gApi.changes().id(result.getChangeId());
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + change.topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    ChangeApi dsOneChange = gApi.changes().id(dsOneChangeInfo._number);
    assertThat(getVote(dsOneChange, "Code-Review").value).isEqualTo(0);
    assertThat(getVote(dsOneChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");
    // It should skip ds_one, since this is a DO NOT MERGE ANYWHERE
    assertThat(dsOneChange.get().subject).contains("skipped:");
    assertThat(dsOneChange.current().files().keySet()).contains("filename");
    assertThat(dsOneChange.current().files().get("filename").linesDeleted).isEqualTo(1);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    ChangeApi dsTwoChange = gApi.changes().id(dsTwoChangeInfo._number);
    assertThat(getVote(dsTwoChange, "Code-Review").value).isEqualTo(0);
    assertThat(getVote(dsTwoChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");
    // It should skip ds_one, since this is a DO NOT MERGE ANYWHERE
    assertThat(dsTwoChange.get().subject).contains("skipped:");
    assertThat(dsTwoChange.current().files().keySet()).contains("filename");
    assertThat(dsTwoChange.current().files().get("filename").linesDeleted).isEqualTo(1);

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    ChangeApi masterChange = gApi.changes().id(masterChangeInfo._number);
    assertThat(getVote(masterChange, "Code-Review").value).isEqualTo(0);
    assertThat(getVote(masterChange, "Code-Review").tag).isEqualTo(null);
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " skipped: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " skipped: " + shortMasterSha);
  }

  @Test
  public void testDownstreamMergeConflict() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    result.assertOkStatus();
    merge(result);
    // Reset to create a sibling
    ObjectId initial = repo().exactRef("HEAD").getLeaf().getObjectId();
    testRepo.reset(initial);
    // Set up a merge conflict between master and ds_one
    PushOneCommit.Result ds1Result =
        createChange(
            testRepo, "ds_one", "subject", "filename", "echo \"Hello asdfsd World\"", "randtopic");
    ds1Result.assertOkStatus();
    merge(ds1Result);
    // Reset to allow our merge conflict to come
    testRepo.reset(initial);
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new change to create the downstreams
    PushOneCommit.Result masterResult =
        pushFactory
            .create(db, admin.getIdent(), testRepo, "subject", "filename", "echo 'Hello World!'")
            .to("refs/for/master");
    masterResult.assertOkStatus();

    // Since there's a conflict with ds_one, there should only be two changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(masterResult.getChangeId()).topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(2);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(0);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    ChangeApi dsTwoChange = gApi.changes().id(dsTwoChangeInfo._number);
    // This is -2 because the -2 vote from master propagated to ds_two
    assertThat(getVote(dsTwoChange, "Code-Review").value).isEqualTo(-2);
    assertThat(getVote(dsTwoChange, "Code-Review").tag).isEqualTo("autogenerated:Automerger");

    ChangeInfo masterChangeInfo = sortedChanges.get(1);
    ChangeApi masterChange = gApi.changes().id(masterChangeInfo._number);
    assertThat(getVote(masterChange, "Code-Review").value).isEqualTo(-2);
    assertThat(getVote(masterChange, "Code-Review").tag).isEqualTo("autogenerated:MergeConflict");
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
  }

  @Test
  public void testTopicEditedListener() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    gApi.changes().id(result.getChangeId()).topic("multiple words");
    gApi.changes().id(result.getChangeId()).topic("singlewordagain");
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic:\"" + gApi.changes().id(result.getChangeId()).topic() + "\"")
            .get();
    assertThat(changesInTopic).hasSize(3);
    // +2 and submit
    merge(result);
  }

  @Test
  public void testTopicEditedListener_withQuotes() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    gApi.changes().id(result.getChangeId()).topic("multiple words");
    gApi.changes().id(result.getChangeId()).topic("with\"quotes\"inside");
    // Gerrit fails to submit changes in the same topic together if it contains quotes.
    gApi.changes().id(result.getChangeId()).topic("without quotes anymore");
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic:{" + gApi.changes().id(result.getChangeId()).topic() + "}")
            .get();
    assertThat(changesInTopic).hasSize(3);
    // +2 and submit
    merge(result);
  }

  @Test
  public void testTopicEditedListener_withBraces() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    gApi.changes().id(result.getChangeId()).topic("multiple words");
    gApi.changes().id(result.getChangeId()).topic("with{braces}inside");
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic:\"" + gApi.changes().id(result.getChangeId()).topic() + "\"")
            .get();
    assertThat(changesInTopic).hasSize(3);
    // +2 and submit
    merge(result);
  }

  @Test
  public void testTopicEditedListener_branchWithBracesAndQuotes() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "branch{}braces"));
    createBranch(new Branch.NameKey(projectName, "branch\"quotes"));
    pushConfig(
        "automerger.config",
        manifestNameKey.get(),
        projectName,
        "branch{}braces",
        "branch\"quotes");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic:\"" + gApi.changes().id(result.getChangeId()).topic() + "\"")
            .get();
    assertThat(changesInTopic).hasSize(3);
    // +2 and submit
    merge(result);
  }

  @Test
  public void testTopicEditedListener_emptyTopic() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", null);
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    // Setting the topic to empty should be a no-op.
    gApi.changes().id(result.getChangeId()).topic("");
    assertThat(gApi.changes().id(result.getChangeId()).topic())
        .isEqualTo(gApi.changes().id(result.getChangeId()).topic());
    // +2 and submit
    merge(result);
  }

  private Project.NameKey defaultSetup() throws Exception {
    Project.NameKey manifestNameKey = createProject("platform/manifest");
    setupTestRepo("default.xml", manifestNameKey, "master", "default.xml");
    setupTestRepo("ds_one.xml", manifestNameKey, "ds_one", "default.xml");
    setupTestRepo("ds_two.xml", manifestNameKey, "ds_two", "default.xml");
    return manifestNameKey;
  }

  private void setupTestRepo(
      String resourceName, Project.NameKey projectNameKey, String branchName, String filename)
      throws Exception {
    TestRepository<InMemoryRepository> repo = cloneProject(projectNameKey, admin);
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));

      PushOneCommit push =
          pushFactory.create(db, admin.getIdent(), repo, "some subject", filename, resourceString);
      push.to("refs/heads/" + branchName).assertOkStatus();
    }
  }

  private void pushConfig(
      String resourceName, String manifestName, String project, String branch1, String branch2)
      throws Exception {
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));

      Config cfg = new Config();
      cfg.fromText(resourceString);
      // Update manifest project path to the result of createProject(resourceName), since it is
      // scoped to the test method
      cfg.setString("global", null, "manifestProject", manifestName);
      if (branch1 != null) {
        cfg.setString("automerger", "master:" + branch1, "setProjects", project);
      }
      if (branch2 != null) {
        cfg.setString("automerger", "master:" + branch2, "setProjects", project);
      }
      PushOneCommit push =
          pushFactory.create(
              db, admin.getIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to(RefNames.REFS_CONFIG).assertOkStatus();
    }
  }

  private void pushDiamondConfig(String manifestName, String project) throws Exception {
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    try (InputStream in = getClass().getResourceAsStream("diamond.config")) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));
      Config cfg = new Config();
      cfg.fromText(resourceString);
      // Update manifest project path to the result of createProject(resourceName), since it is
      // scoped to the test method
      cfg.setString("global", null, "manifestProject", manifestName);
      cfg.setString("automerger", "master:left", "setProjects", project);
      cfg.setString("automerger", "master:right", "setProjects", project);
      cfg.setString("automerger", "left:bottom", "setProjects", project);
      cfg.setString("automerger", "right:bottom", "setProjects", project);
      PushOneCommit push =
          pushFactory.create(
              db, admin.getIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to(RefNames.REFS_CONFIG).assertOkStatus();
    }
  }

  private ApprovalInfo getVote(ChangeApi change, String label) throws RestApiException {
    return change.get(EnumSet.of(ListChangesOption.DETAILED_LABELS)).labels.get(label).all.get(0);
  }

  private List<ChangeInfo> sortedChanges(List<ChangeInfo> changes) {
    List<ChangeInfo> listCopy = new ArrayList<ChangeInfo>(changes);
    Collections.sort(
        listCopy,
        new Comparator<ChangeInfo>() {
          @Override
          public int compare(ChangeInfo c1, ChangeInfo c2) {
            int compareResult = c1.branch.compareTo(c2.branch);
            if (compareResult == 0) {
              return Integer.compare(c1._number, c2._number);
            }
            return compareResult;
          }
        });
    return listCopy;
  }
}
