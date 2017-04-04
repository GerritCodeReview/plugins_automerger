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

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
@TestPlugin(
  name = "automerger",
  sysModule = "com.googlesource.gerrit.plugins.automerger.AutomergerModule"
)
public class DownstreamCreatorIT extends LightweightPluginDaemonTest {
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

  private void pushConfig(String resourceName, String manifestName, String project)
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
      cfg.setString("automerger", "master:ds_one", "setProjects", project);
      cfg.setString("automerger", "master:ds_two", "setProjects", project);
      PushOneCommit push =
          pushFactory.create(
              db, admin.getIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to("refs/meta/config").assertOkStatus();
    }
  }

  @Test
  public void testExpectedFlow() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName);
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    // Check that there are the correct number of changes in the topic
    List<ChangeInfo> changesInTopic =
        gApi.changes().query("topic: " + gApi.changes().id(result.getChangeId()).topic()).get();
    assertThat(changesInTopic).hasSize(3);
    // +2 and submit
    ReviewInput review = new ReviewInput();
    review.label("Code-Review", 2);
    gApi.changes().id(result.getChangeId()).current().review(review);
    gApi.changes().id(result.getChangeId()).current().submit();
  }

  @Test
  public void testBlankMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange("DO NOT MERGE subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName);
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId(), "DO NOT MERGE subject", "filename", "content");
    result.assertOkStatus();

    ChangeApi change = gApi.changes().id(result.getChangeId());
    BinaryResult content = change.current().file("filename").content();

    List<ChangeInfo> changesInTopic = gApi.changes().query("topic: " + change.topic()).get();
    assertThat(changesInTopic).hasSize(3);
    for (ChangeInfo c : changesInTopic) {
      ChangeApi downstreamChange = gApi.changes().id(c._number);
      // It should skip ds_one, since this is a DO NOT MERGE
      if (c.branch.equals("ds_one")) {
        assertThat(downstreamChange.get().subject).contains("skipped:");
        assertThat(downstreamChange.current().files().keySet()).contains("filename");
        assertThat(downstreamChange.current().files().get("filename").linesDeleted).isEqualTo(1);
      } else if (c.branch.equals("ds_two")) {
        // It should not skip ds_two, since it is marked with mergeAll: true
        assertThat(downstreamChange.get().subject).doesNotContain("skipped:");
        BinaryResult downstreamContent = downstreamChange.current().file("filename").content();
        assertThat(downstreamContent.asString()).isEqualTo(content.asString());
      } else {
        assertThat(c.branch).isEqualTo("master");
      }
    }
  }

  @Test
  public void testAlwaysBlankMerge() throws Exception {
    Project.NameKey manifestNameKey = defaultSetup();
    // Create initial change
    PushOneCommit.Result result =
        createChange("DO NOT MERGE ANYWHERE subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    createBranch(new Branch.NameKey(projectName, "ds_two"));
    pushConfig("automerger.config", manifestNameKey.get(), projectName);
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId(), "DO NOT MERGE ANYWHERE subject", "filename", "content");
    result.assertOkStatus();

    ChangeApi change = gApi.changes().id(result.getChangeId());

    List<ChangeInfo> changesInTopic = gApi.changes().query("topic: " + change.topic()).get();
    assertThat(changesInTopic).hasSize(3);
    for (ChangeInfo c : changesInTopic) {
      ChangeApi downstreamChange = gApi.changes().id(c._number);
      // It should skip ds_one, since this is a DO NOT MERGE
      if (c.branch.equals("ds_one") || c.branch.equals("ds_two")) {
        assertThat(downstreamChange.get().subject).contains("skipped:");
        assertThat(downstreamChange.current().files().keySet()).contains("filename");
        assertThat(downstreamChange.current().files().get("filename").linesDeleted).isEqualTo(1);
      } else {
        assertThat(c.branch).isEqualTo("master");
      }
    }
  }
}
