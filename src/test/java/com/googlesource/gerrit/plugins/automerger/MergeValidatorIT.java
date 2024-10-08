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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.io.CharStreams;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.HashtagsInput;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

@TestPlugin(
    name = "automerger",
    sysModule = "com.googlesource.gerrit.plugins.automerger.AutomergerModule")
public class MergeValidatorIT extends LightweightPluginDaemonTest {
  private void pushConfig(String resourceName, String project, String branch, ChangeMode changeMode) throws Exception {
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString =
          CharStreams.toString(new InputStreamReader(in, StandardCharsets.UTF_8));

      String cherryPickMode = (changeMode == ChangeMode.MERGE) ? "False" : "True";
      Config cfg = new Config();
      cfg.fromText(resourceString);
      cfg.setString("global", null, "cherryPickMode", cherryPickMode);
      // Update manifest project path to the result of createProject(resourceName), since it is
      // scoped to the test method
      cfg.setString("automerger", "master:" + branch, "setProjects", project);
      PushOneCommit push =
          pushFactory.create(
              admin.newIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to(RefNames.REFS_CONFIG).assertOkStatus();
    }
  }

  private void pushConfig(String resourceName, String project, String branch) throws Exception {
    pushConfig(resourceName, project, branch, ChangeMode.MERGE);
  }

  @Test
  public void testNoMissingDownstreamMerges() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    pushConfig("automerger.config", projectName, "ds_one");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    merge(result);
  }

  @Test
  public void testNoMissingDownstreamMerges_abandonedDownstream() throws Exception {
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    pushConfig("automerger.config", projectName, "ds_one");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();

    // Abandon downstream change.
    List<ChangeInfo> changesInTopic =
        gApi.changes()
            .query("topic: " + gApi.changes().id(result.getChangeId()).topic())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    assertThat(changesInTopic).hasSize(2);
    List<ChangeInfo> sortedChanges = sortedChanges(changesInTopic);
    assertThat(sortedChanges.get(0).branch).isEqualTo("ds_one");
    gApi.changes().id(sortedChanges.get(0)._number).abandon();

    int changeNumber = result.getChange().getId().get();
    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> merge(result));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Failed to submit 1 change due to the following problems:\nChange "
                + changeNumber
                + ": Missing downstream branches ds_one. Please recreate the automerges.");
  }

  @Test
  public void testNoMissingDownstreamMerges_branchWithQuotes() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(BranchNameKey.create(projectName, "branch\"quotes"));
    pushConfig("automerger.config", projectName, "branch\"quotes");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    merge(result);
  }

  @Test
  public void testNoMissingDownstreamMerges_branchWithBraces() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(BranchNameKey.create(projectName, "branch{}braces"));
    pushConfig("automerger.config", projectName, "branch{}braces");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    merge(result);
  }

  @Test
  public void testMultiWordTopic() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(BranchNameKey.create(projectName, "ds_one"));
    pushConfig("automerger.config", projectName, "ds_one");
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();

    gApi.changes().id(result.getChangeId()).topic("multiple words");
    merge(result);
  }

  @Test
  public void testMissingDownstreamMerges() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    pushConfig("automerger.config", result.getChange().project().get(), "ds_one");
    result.assertOkStatus();
    int changeNumber = result.getChange().getId().get();
    // Assert we are missing downstreams
    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> merge(result));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Failed to submit 1 change due to the following problems:\nChange "
                + changeNumber
                + ": Missing downstream branches ds_one. Please recreate the automerges.");
  }

  @Test
  public void testMissingDownstreamMerges_custom() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");
    pushConfig("alternate.config", result.getChange().project().get(), "ds_one");
    result.assertOkStatus();
    int changeNumber = result.getChange().getId().get();
    // Assert we are missing downstreams
    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> merge(result));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Failed to submit 1 change due to the following problems:\nChange "
                + changeNumber
                + ": there is no ds_one");
  }

  @Test
  public void testSkippedCherryPick() throws Exception {
    // Create initial change
    PushOneCommit.Result result =
        createChange(testRepo, "master", "subject", "filename", "content", "testtopic");

    // Add the skip hashtag.
    Set set = new HashSet<>();
    set.add("am_skip_ds_one");
    HashtagsInput input = new HashtagsInput(set);
    gApi.changes().id(result.getChangeId()).setHashtags(input);

    pushConfig("automerger.config", result.getChange().project().get(), "ds_one", ChangeMode.CHERRY_PICK);
    result.assertOkStatus();

    // Should be able to merge successfully.
    merge(result);
  }

  private List<ChangeInfo> sortedChanges(List<ChangeInfo> changes) {
    List<ChangeInfo> listCopy = new ArrayList<>(changes);
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
