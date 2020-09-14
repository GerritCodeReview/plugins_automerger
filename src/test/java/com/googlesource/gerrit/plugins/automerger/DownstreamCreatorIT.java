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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.OptionalSubject.optionals;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.blockLabel;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.labelPermissionKey;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;
import static com.google.gerrit.extensions.client.ListChangesOption.MESSAGES;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.util.Comparator.comparing;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.group.GroupOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.accounts.AccountApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.RebaseInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.testing.ConfigSuite;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.automerger.helpers.ConfigOption;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

@TestPlugin(
    name = "automerger",
    sysModule = "com.googlesource.gerrit.plugins.automerger.AutomergerModule")
public class DownstreamCreatorIT extends LightweightPluginDaemonTest {
  @Inject private GroupOperations groupOperations;
  @Inject private ProjectOperations projectOperations;

  @ConfigSuite.Default
  public static Config minimalEventPayload() {
    Config cfg = new Config();
    // Expect only identifiers in internal Gerrit events
    cfg.setStringList(
        "event",
        "payload",
        "listChangeOptions",
        ImmutableList.of("SKIP_MERGEABLE", "SKIP_DIFFSTAT"));
    return cfg;
  }

  @Test
  public void testExpectedFlow() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOption(CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    assertAutomergerChangeCreatedMessage(dsOneChangeInfo.id);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    assertAutomergerChangeCreatedMessage(dsTwoChangeInfo.id);

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    assertCodeReviewMissing(masterChangeInfo.id);
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);

    // +2 and submit
    merge(result);
    assertCodeReview(masterChangeInfo.id, 2, null);
    assertCodeReview(dsOneChangeInfo.id, 2, "autogenerated:Automerger");
    assertCodeReview(dsTwoChangeInfo.id, 2, "autogenerated:Automerger");
  }

  @Test
  public void testDiamondMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result initialResult = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = initialResult.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "left"));
    createBranch(BranchNameKey.create(projectName, "right"));
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
    createBranch(BranchNameKey.create(projectName, "bottom"));
    pushDiamondConfig(manifestNameKey.get(), projectName);
    // After we upload our config, we upload a new patchset to create the downstreams
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename2", "echo Hello", "sometopic");
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(CURRENT_REVISION, CURRENT_COMMIT)
            .get();
    assertThat(changesInTopic).hasSize(5);
    // +2 and submit
    merge(result);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Should create two changes on bottom, since left and right are different.
    ChangeInfo bottomChangeInfoA = sortedChanges.get(0);
    assertThat(bottomChangeInfoA.branch).isEqualTo("bottom");
    assertCodeReview(bottomChangeInfoA.id, 2, "autogenerated:Automerger");

    ChangeInfo bottomChangeInfoB = sortedChanges.get(1);
    assertThat(bottomChangeInfoB.branch).isEqualTo("bottom");
    assertCodeReview(bottomChangeInfoB.id, 2, "autogenerated:Automerger");

    ChangeInfo leftChangeInfo = sortedChanges.get(2);
    assertThat(leftChangeInfo.branch).isEqualTo("left");
    assertCodeReview(leftChangeInfo.id, 2, "autogenerated:Automerger");

    ChangeInfo masterChangeInfo = sortedChanges.get(3);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    assertCodeReview(masterChangeInfo.id, 2, null);

    ChangeInfo rightChangeInfo = sortedChanges.get(4);
    assertThat(rightChangeInfo.branch).isEqualTo("right");
    assertCodeReview(rightChangeInfo.id, 2, "autogenerated:Automerger");

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

    // Either bottomChangeInfoA came from left and bottomChangeInfoB came from right, or vice versa
    // We don't know which, so we use the if condition to check
    String bottomChangeInfoASecondParent = getParent(bottomChangeInfoA, 1);
    if (bottomChangeInfoASecondParent.equals(leftChangeInfo.currentRevision)) {
      assertThat(bottomChangeInfoA.subject)
          .isEqualTo(
              "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortLeftSha);
      assertThat(bottomChangeInfoB.subject)
          .isEqualTo(
              "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortRightSha);
    } else {
      assertThat(bottomChangeInfoA.subject)
          .isEqualTo(
              "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortRightSha);
      assertThat(bottomChangeInfoB.subject)
          .isEqualTo(
              "[automerger] " + masterSubject + " am: " + shortMasterSha + " am: " + shortLeftSha);
    }
  }

  @Test
  public void testChangeStack() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    pushSimpleConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    PushOneCommit.Result result2 =
        createChange(testRepo, "master", "subject2", "filename2", "content2", "testtopic");
    result2.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(ALL_REVISIONS, CURRENT_COMMIT)
            .get();
    assertThat(changesInTopic).hasSize(4);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Change A'
    ChangeInfo aPrime = sortedChanges.get(0);
    assertThat(aPrime.branch).isEqualTo("ds_one");
    // Change B'
    ChangeInfo bPrime = sortedChanges.get(1);
    assertThat(bPrime.branch).isEqualTo("ds_one");
    String bPrimeFirstParent = getParent(bPrime, 0);
    assertThat(aPrime.currentRevision).isEqualTo(bPrimeFirstParent);

    // Change A
    ChangeInfo a = sortedChanges.get(2);
    assertThat(a.branch).isEqualTo("master");
    // Change B
    ChangeInfo b = sortedChanges.get(3);
    assertThat(b.branch).isEqualTo("master");
    String bFirstParent = getParent(b, 0);
    // Check that first parent of B is A
    assertThat(bFirstParent).isEqualTo(a.currentRevision);

    // Ensure that commit subjects are correct
    String shortASha = a.currentRevision.substring(0, 10);
    assertThat(a.subject).doesNotContainMatch("automerger");
    assertThat(aPrime.subject).isEqualTo("[automerger] test commit am: " + shortASha);
  }

  @Test
  public void testChangeStack_rebaseAfterUpload() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Save initial ref at HEAD
    ObjectId initial = repo().exactRef("HEAD").getLeaf().getObjectId();
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    pushSimpleConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();

    // Reset to initial ref to create a sibling
    testRepo.reset(initial);

    PushOneCommit.Result result2 =
        createChange(testRepo, "master", "subject2", "filename2", "content2", "testtopic");
    result2.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(ALL_REVISIONS, CURRENT_COMMIT)
            .get();
    assertThat(changesInTopic).hasSize(4);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Check the first downstream change A'
    ChangeInfo aPrime = sortedChanges.get(0);
    assertThat(aPrime.branch).isEqualTo("ds_one");
    // Check the second downstream change B'
    ChangeInfo bPrime = sortedChanges.get(1);
    assertThat(bPrime.branch).isEqualTo("ds_one");
    // Check that B' does not have a first parent of A' yet
    String bPrimeFirstParent = getParent(bPrime, 0);
    assertThat(aPrime.currentRevision).isNotEqualTo(bPrimeFirstParent);

    // Change A
    ChangeInfo a = sortedChanges.get(2);
    assertThat(a.branch).isEqualTo("master");
    // Change B
    ChangeInfo b = sortedChanges.get(3);
    assertThat(b.branch).isEqualTo("master");
    String masterChangeInfo2FirstParentSha = getParent(b, 0);
    // Check that first parent of B is not A
    assertThat(a.currentRevision).isNotEqualTo(masterChangeInfo2FirstParentSha);

    // Rebase B on A
    RebaseInput rebaseInput = new RebaseInput();
    rebaseInput.base = a.currentRevision;
    gApi.changes().id(b.changeId).rebase(rebaseInput);

    // Check that B is now based on A, and B' is now based on A'
    ChangeInfo bAfterRebase = gApi.changes().id(b.changeId).get();
    String bAfterRebaseFirstParent = getParent(bAfterRebase, 0);
    assertThat(bAfterRebaseFirstParent).isEqualTo(a.currentRevision);

    ChangeInfo bPrimeAfterRebase = gApi.changes().id(bPrime.changeId).get();
    String bPrimeAfterRebaseFirstParent = getParent(bPrimeAfterRebase, 0);
    assertThat(bPrimeAfterRebaseFirstParent).isEqualTo(aPrime.currentRevision);
  }

  @Test
  public void testBlankMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange(
            testRepo, "master", "DO NOT MERGE subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId(), "DO NOT MERGE subject", "filename", "content");
    result.assertOkStatus();

    ChangeApi change = gApi.changes().id(result.getChangeId());
    BinaryResult content = change.current().file("filename").content();

    List<ChangeInfo> changesInTopic =
        gApi.changes().query("topic: " + change.topic()).withOption(CURRENT_REVISION).get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    assertAutomergerChangeCreatedMessage(dsOneChangeInfo.id);
    // It should skip ds_one, since this is a DO NOT MERGE
    ChangeApi dsOneChange = gApi.changes().id(dsOneChangeInfo._number);
    assertThat(dsOneChange.get().subject).contains("skipped:");
    assertThat(dsOneChange.current().files().keySet()).contains("filename");
    assertThat(dsOneChange.current().files().get("filename").linesDeleted).isEqualTo(1);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    assertAutomergerChangeCreatedMessage(dsTwoChangeInfo.id);
    // It should not skip ds_two, since it is marked with mergeAll: true
    ChangeApi dsTwoChange = gApi.changes().id(dsTwoChangeInfo._number);
    assertThat(dsTwoChange.get().subject).doesNotContain("skipped:");
    BinaryResult dsTwoContent = dsTwoChange.current().file("filename").content();
    assertThat(dsTwoContent.asString()).isEqualTo(content.asString());

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    assertCodeReviewMissing(masterChangeInfo.id);
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject)
        .isEqualTo("[automerger skipped] " + masterSubject + " skipped: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
  }

  @Test
  public void testAlwaysBlankMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange(
            testRepo,
            "master",
            "DO NOT MERGE ANYWHERE subject",
            "filename",
            "content",
            "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId(), "DO NOT MERGE ANYWHERE subject", "filename", "content");
    result.assertOkStatus();

    ChangeApi change = gApi.changes().id(result.getChangeId());
    List<ChangeInfo> changesInTopic =
        gApi.changes().query("topic: " + change.topic()).withOption(CURRENT_REVISION).get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    assertAutomergerChangeCreatedMessage(dsOneChangeInfo.id);
    ChangeApi dsOneChange = gApi.changes().id(dsOneChangeInfo._number);
    // It should skip ds_one, since this is a DO NOT MERGE ANYWHERE
    assertThat(dsOneChange.get().subject).contains("skipped:");
    assertThat(dsOneChange.current().files().keySet()).contains("filename");
    assertThat(dsOneChange.current().files().get("filename").linesDeleted).isEqualTo(1);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    assertAutomergerChangeCreatedMessage(dsTwoChangeInfo.id);
    ChangeApi dsTwoChange = gApi.changes().id(dsTwoChangeInfo._number);
    // It should skip ds_one, since this is a DO NOT MERGE ANYWHERE
    assertThat(dsTwoChange.get().subject).contains("skipped:");
    assertThat(dsTwoChange.current().files().keySet()).contains("filename");
    assertThat(dsTwoChange.current().files().get("filename").linesDeleted).isEqualTo(1);

    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    assertCodeReviewMissing(masterChangeInfo.id);
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsOneChangeInfo.subject)
        .isEqualTo("[automerger skipped] " + masterSubject + " skipped: " + shortMasterSha);
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger skipped] " + masterSubject + " skipped: " + shortMasterSha);
  }

  @Test
  public void testDownstreamMergeConflict() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
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
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
    // After we upload our config, we upload a new change to create the downstreams
    PushOneCommit.Result masterResult =
        pushFactory
            .create(admin.newIdent(), testRepo, "subject", "filename", "echo 'Hello World!'")
            .to("refs/for/master");
    masterResult.assertOkStatus();

    // Since there's a conflict with ds_one, there should only be two changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(masterResult.getChangeId()).topic())
            .withOptions(CURRENT_REVISION, MESSAGES)
            .get();
    assertThat(changesInTopic).hasSize(2);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(0);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    // This is -2 because the -2 vote from master propagated to ds_two
    assertCodeReview(dsTwoChangeInfo.id, -2, "autogenerated:Automerger");

    ChangeInfo masterChangeInfo = sortedChanges.get(1);
    assertCodeReview(masterChangeInfo.id, -2, "autogenerated:MergeConflict");
    assertThat(masterChangeInfo.branch).isEqualTo("master");

    // Make sure that merge conflict message is still added
    List<String> messages = new ArrayList<>();
    for (ChangeMessageInfo cmi : masterChangeInfo.messages) {
      messages.add(cmi.message);
    }
    assertThat(messages).contains("Patch Set 1: Code-Review-2\n\nMerge conflict found on ds_one");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
  }

  @Test
  public void testRestrictedVotePermissions() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
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
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");

    // Block Code Review label to test restrictions
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            blockLabel("Code-Review")
                .ref("refs/heads/*")
                .group(SystemGroupBackend.CHANGE_OWNER)
                .range(-2, 2))
        .update();

    // After we upload our config, we upload a new change to create the downstreams
    PushOneCommit.Result masterResult =
        pushFactory
            .create(admin.newIdent(), testRepo, "subject", "filename", "echo 'Hello World!'")
            .to("refs/for/master");
    masterResult.assertOkStatus();

    // Since there's a conflict with ds_one, there should only be two changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(masterResult.getChangeId()).topic())
            .withOptions(CURRENT_REVISION, MESSAGES)
            .get();
    assertThat(changesInTopic).hasSize(2);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(0);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    assertAutomergerChangeCreatedMessage(dsTwoChangeInfo.id);

    ChangeInfo masterChangeInfo = sortedChanges.get(1);
    // This is not set because the -2 vote on master failed due to permissions
    assertCodeReviewMissing(masterChangeInfo.id);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    assertThat(getLastMessage(masterChangeInfo.id).tag).isEqualTo("autogenerated:MergeConflict");

    // Make sure that merge conflict message is still added
    List<String> messages = new ArrayList<>();
    for (ChangeMessageInfo cmi : masterChangeInfo.messages) {
      messages.add(cmi.message);
    }
    assertThat(messages).contains("Patch Set 1:\n\nMerge conflict found on ds_one");

    // Ensure that commit subjects are correct
    String masterSubject = masterChangeInfo.subject;
    String shortMasterSha = masterChangeInfo.currentRevision.substring(0, 10);
    assertThat(masterChangeInfo.subject).doesNotContainMatch("automerger");
    assertThat(dsTwoChangeInfo.subject)
        .isEqualTo("[automerger] " + masterSubject + " am: " + shortMasterSha);
  }

  @Test
  public void testTopicEditedListener_withQuotes() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
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
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    pushDefaultConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one", "ds_two");
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
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "branch{}braces"));
    createBranch(BranchNameKey.create(projectName, "branch\"quotes"));
    pushDefaultConfig(
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
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().project().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    pushSimpleConfig("automerger.config", manifestNameKey.get(), projectName, "ds_one");
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

  @Test
  public void testContextUser() throws Exception {
    // Branch flow for contextUser is master -> ds_one -> ds_two
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result initialResult = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    Project.NameKey projectNameKey = initialResult.getChange().project();
    String projectName = projectNameKey.get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    initialResult.assertOkStatus();
    merge(initialResult);

    // Create normalUserGroup, containing user, and contextUserGroup, containing contextUser
    String normalUserGroup = groupOperations.newGroup().name("normalUserGroup").create().get();
    gApi.groups().id(normalUserGroup).addMembers(user.id().toString());
    AccountApi contextUserApi = gApi.accounts().create("someContextUser");
    String contextUserGroup = groupOperations.newGroup().name("contextUserGroup").create().get();
    gApi.groups().id(contextUserGroup).addMembers(contextUserApi.get().name);

    // Grant exclusive +2 to context user
    projectOperations
        .project(projectNameKey)
        .forUpdate()
        .add(
            allowLabel("Code-Review")
                .ref("refs/heads/ds_one")
                .group(AccountGroup.UUID.parse(gApi.groups().id(contextUserGroup).get().id))
                .range(-2, 2))
        .setExclusiveGroup(labelPermissionKey("Code-Review").ref("refs/heads/ds_one"), true)
        .update();
    pushContextUserConfig(
        manifestNameKey.get(), projectName, contextUserApi.get()._accountId.toString());

    // After we upload our config, we upload a new patchset to create the downstreams
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename2", "echo Hello", "sometopic");
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(CURRENT_REVISION, CURRENT_COMMIT)
            .get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Check that downstream has no Code-Review
    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    assertCodeReviewMissing(dsOneChangeInfo.id);

    // Try to +2 master and see it succeed to +2 master and ds_one
    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    assertCodeReviewMissing(masterChangeInfo.id);
    approve(masterChangeInfo.id);
    assertCodeReview(masterChangeInfo.id, 2, null);
    assertCodeReview(dsOneChangeInfo.id, 2, "autogenerated:Automerger");

    // Try to +2 downstream and see it fail
    AuthException thrown = assertThrows(AuthException.class, () -> approve(dsOneChangeInfo.id));
    assertThat(thrown).hasMessageThat().contains("Applying label \"Code-Review\": 2 is restricted");
  }

  @Test
  public void testContextUser_downstreamHighestVote() throws Exception {
    // Branch flow for contextUser is master -> ds_one -> ds_two
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result initialResult = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    Project.NameKey projectNameKey = initialResult.getChange().project();
    String projectName = projectNameKey.get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    initialResult.assertOkStatus();
    merge(initialResult);

    // Create normalUserGroup, containing user, and contextUserGroup, containing contextUser
    String normalUserGroup = groupOperations.newGroup().name("normalUserGroup").create().get();
    gApi.groups().id(normalUserGroup).addMembers(user.id().toString());
    AccountApi contextUserApi = gApi.accounts().create("randomContextUser");
    String contextUserGroup = groupOperations.newGroup().name("contextUserGroup").create().get();
    gApi.groups().id(contextUserGroup).addMembers(contextUserApi.get().name);

    // Grant +2 to context user, since it doesn't have it by default
    projectOperations
        .project(projectNameKey)
        .forUpdate()
        .add(
            allowLabel("Code-Review")
                .ref("refs/heads/*")
                .group(AccountGroup.UUID.parse(gApi.groups().id(contextUserGroup).get().id))
                .range(-2, 2))
        .update();
    pushContextUserConfig(
        manifestNameKey.get(), projectName, contextUserApi.get()._accountId.toString());

    // After we upload our config, we upload a new patchset to create the downstreams
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename2", "echo Hello", "sometopic");
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(CURRENT_REVISION, CURRENT_COMMIT)
            .get();
    assertThat(changesInTopic).hasSize(3);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Check that downstream has no Code-Review
    ChangeInfo dsOneChangeInfo = sortedChanges.get(0);
    assertThat(dsOneChangeInfo.branch).isEqualTo("ds_one");
    assertCodeReviewMissing(dsOneChangeInfo.id);

    // Try to +1 master and see it succeed to +1 master and ds_one
    ChangeInfo masterChangeInfo = sortedChanges.get(2);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    assertCodeReviewMissing(masterChangeInfo.id);
    recommend(masterChangeInfo.id);
    assertCodeReview(masterChangeInfo.id, 1, null);
    assertCodeReview(dsOneChangeInfo.id, 1, "autogenerated:Automerger");

    ChangeInfo dsTwoChangeInfo = sortedChanges.get(1);
    assertThat(dsTwoChangeInfo.branch).isEqualTo("ds_two");
    assertCodeReview(dsTwoChangeInfo.id, 1, "autogenerated:Automerger");

    // +2 ds_one and see that it overrides the +1 of the contextUser
    approve(dsOneChangeInfo.id);
    assertCodeReview(dsOneChangeInfo.id, 2, null);
    assertCodeReview(dsTwoChangeInfo.id, 2, "autogenerated:Automerger");
    // +0 ds_one and see that it goes back to the +1 of the contextUser
    gApi.changes().id(dsOneChangeInfo.id).revision("current").review(ReviewInput.noScore());
    assertCodeReview(dsOneChangeInfo.id, 1, "autogenerated:Automerger");
    assertCodeReview(dsTwoChangeInfo.id, 1, "autogenerated:Automerger");
  }

  @Test
  public void testContextUser_mergeConflictOnDownstreamVotesOnTopLevel() throws Exception {
    // Branch flow for contextUser is master -> ds_one -> ds_two
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result initialResult = createChange("subject", "filename", "echo Hello");
    // Project name is scoped by test, so we need to get it from our initial change
    Project.NameKey projectNameKey = initialResult.getChange().project();
    String projectName = projectNameKey.get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    createBranch(BranchNameKey.create(projectName, "ds_two"));
    initialResult.assertOkStatus();
    merge(initialResult);

    // Reset to create a sibling
    ObjectId initial = repo().exactRef("HEAD").getLeaf().getObjectId();
    PushOneCommit.Result ds1Result =
        createChange(
            testRepo, "ds_one", "subject", "filename", "echo \"Hello asdfsd World\"", "randtopic");
    ds1Result.assertOkStatus();
    merge(ds1Result);
    // Reset to allow our merge conflict to come
    testRepo.reset(initial);
    // Set up a merge conflict between ds_one and ds_two
    PushOneCommit.Result ds2Result =
        createChange(
            testRepo, "ds_two", "subject", "filename", "echo yo World wutup wutup", "randtopic");
    ds2Result.assertOkStatus();
    merge(ds2Result);
    testRepo.reset(initial);

    // Create normalUserGroup, containing current user, and contextUserGroup, containing contextUser
    String normalUserGroup = groupOperations.newGroup().name("normalUserGroup").create().get();
    gApi.groups().id(normalUserGroup).addMembers(user.id().toString());
    AccountApi contextUserApi = gApi.accounts().create("asdfContextUser");
    String contextUserGroup = groupOperations.newGroup().name("contextUserGroup").create().get();
    gApi.groups().id(contextUserGroup).addMembers(contextUserApi.get().name);

    // Grant +2 to context user, since it doesn't have it by default
    projectOperations
        .project(projectNameKey)
        .forUpdate()
        .add(
            allowLabel("Code-Review")
                .ref("refs/heads/*")
                .group(AccountGroup.UUID.parse(gApi.groups().id(contextUserGroup).get().id))
                .range(-2, 2))
        .update();
    pushContextUserConfig(
        manifestNameKey.get(), projectName, contextUserApi.get()._accountId.toString());

    // After we upload our config, we upload a new patchset to create the downstreams
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename2", "echo Hello", "sometopic");
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOptions(CURRENT_REVISION, CURRENT_COMMIT)
            .get();
    // There should only be two, as ds_one to ds_two should be a merge conflict
    assertThat(changesInTopic).hasSize(2);

    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);

    // Check that master is at Code-Review -2
    ChangeInfo masterChangeInfo = sortedChanges.get(1);
    assertThat(masterChangeInfo.branch).isEqualTo("master");
    assertCodeReview(masterChangeInfo.id, -2, "autogenerated:MergeConflict");
  }

  private Project.NameKey defaultSetup() throws Exception {
    Project.NameKey manifestNameKey =
        projectOperations.newProject().name(name("platform/manifest")).create();
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
          pushFactory.create(admin.newIdent(), repo, "some subject", filename, resourceString);
      push.to("refs/heads/" + branchName).assertOkStatus();
    }
  }

  private void pushConfig(List<ConfigOption> cfgOptions, String resourceName) throws Exception {
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));

      Config cfg = new Config();
      cfg.fromText(resourceString);

      for (ConfigOption cfgOption : cfgOptions) {
        cfg.setString(cfgOption.section, cfgOption.subsection, cfgOption.key, cfgOption.value);
      }

      PushOneCommit push =
          pushFactory.create(
              admin.newIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to(RefNames.REFS_CONFIG).assertOkStatus();
    }
  }

  private void pushSimpleConfig(
      String resourceName, String manifestName, String project, String branch1) throws Exception {
    List<ConfigOption> options = new ArrayList<>();
    options.add(new ConfigOption("global", null, "manifestProject", manifestName));
    options.add(new ConfigOption("automerger", "master:" + branch1, "setProjects", project));
    pushConfig(options, resourceName);
  }

  private void pushDefaultConfig(
      String resourceName, String manifestName, String project, String branch1, String branch2)
      throws Exception {
    List<ConfigOption> options = new ArrayList<>();
    options.add(new ConfigOption("global", null, "manifestProject", manifestName));
    options.add(new ConfigOption("automerger", "master:" + branch1, "setProjects", project));
    options.add(new ConfigOption("automerger", "master:" + branch2, "setProjects", project));
    pushConfig(options, resourceName);
  }

  private void pushDiamondConfig(String manifestName, String project) throws Exception {
    List<ConfigOption> options = new ArrayList<>();
    options.add(new ConfigOption("global", null, "manifestProject", manifestName));
    options.add(new ConfigOption("automerger", "master:left", "setProjects", project));
    options.add(new ConfigOption("automerger", "master:right", "setProjects", project));
    options.add(new ConfigOption("automerger", "left:bottom", "setProjects", project));
    options.add(new ConfigOption("automerger", "right:bottom", "setProjects", project));
    pushConfig(options, "diamond.config");
  }

  private void pushContextUserConfig(String manifestName, String project, String contextUserId)
      throws Exception {
    List<ConfigOption> options = new ArrayList<>();
    options.add(new ConfigOption("global", null, "manifestProject", manifestName));
    options.add(new ConfigOption("global", null, "contextUserId", contextUserId));
    options.add(new ConfigOption("automerger", "master:ds_one", "setProjects", project));
    options.add(new ConfigOption("automerger", "ds_one:ds_two", "setProjects", project));
    pushConfig(options, "context_user.config");
  }

  private Optional<ApprovalInfo> getCodeReview(String id) throws RestApiException {
    List<ApprovalInfo> approvals =
        gApi.changes().id(id).get(DETAILED_LABELS).labels.get("Code-Review").all;
    if (approvals == null) {
      return Optional.empty();
    }
    return approvals.stream().max(comparing(a -> a.value));
  }

  private void assertCodeReview(String id, int expectedValue, @Nullable String expectedTag)
      throws RestApiException {
    Optional<ApprovalInfo> vote = getCodeReview(id);
    assertWithMessage("Code-Review vote").about(optionals()).that(vote).isPresent();
    assertWithMessage("value of Code-Review vote").that(vote.get().value).isEqualTo(expectedValue);
    assertWithMessage("tag of Code-Review vote").that(vote.get().tag).isEqualTo(expectedTag);
  }

  private void assertCodeReviewMissing(String id) throws RestApiException {
    assertThat(getCodeReview(id)).isEmpty();
  }

  private void assertAutomergerChangeCreatedMessage(String id) throws RestApiException {
    ChangeMessageInfo message = getLastMessage(id);
    assertThat(message.message).contains("Automerger change created!");
    assertThat(message.tag).isEqualTo("autogenerated:Automerger");
  }

  private ChangeMessageInfo getLastMessage(String id) throws RestApiException {
    List<ChangeMessageInfo> messages = gApi.changes().id(id).messages();
    assertThat(messages).isNotEmpty();
    return Iterables.getLast(messages);
  }

  private ImmutableList<ChangeInfo> sortedChanges(List<ChangeInfo> changes) {
    return changes.stream()
        .sorted(comparing((ChangeInfo c) -> c.branch).thenComparing(c -> c._number))
        .collect(toImmutableList());
  }

  public String getParent(ChangeInfo info, int number) {
    return info.revisions.get(info.currentRevision).commit.parents.get(number).commit;
  }
}
