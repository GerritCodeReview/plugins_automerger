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

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.RefNames;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

@TestPlugin(
  name = "automerger",
  sysModule = "com.googlesource.gerrit.plugins.automerger.AutomergerModule"
)
public class MergeValidatorTest extends LightweightPluginDaemonTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  
  private void pushConfig(String resourceName, String project) throws Exception {
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));

      Config cfg = new Config();
      cfg.fromText(resourceString);
      // Update manifest project path to the result of createProject(resourceName), since it is
      // scoped to the test method
      cfg.setString("automerger", "master:ds_one", "setProjects", project);
      PushOneCommit push =
          pushFactory.create(
              db, admin.getIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to("refs/meta/config").assertOkStatus();
    }
  }

  @Test
  public void testNoMissingDownstreamMerges() throws Exception {
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    // Project name is scoped by test, so we need to get it from our initial change
    String projectName = result.getChange().change().getProject().get();
    createBranch(new Branch.NameKey(projectName, "ds_one"));
    pushConfig("automerger.config", projectName);
    // After we upload our config, we upload a new patchset to create the downstreams
    amendChange(result.getChangeId());
    result.assertOkStatus();
    // +2 and submit
    ReviewInput review = new ReviewInput();
    review.label("Code-Review", 2);
    gApi.changes().id(result.getChangeId()).current().review(review);
    gApi.changes().id(result.getChangeId()).current().submit();
   
  }

  @Test
  public void testMissingDownstreamMerges() throws Exception {
    // Create initial change
    PushOneCommit.Result result = createChange("subject", "filename", "content", "testtopic");
    pushConfig("automerger.config", result.getChange().change().getProject().get());
    result.assertOkStatus();
    ReviewInput review = new ReviewInput();
    review.label("Code-Review", 2);
    gApi.changes().id(result.getChangeId()).current().review(review);
    // Assert we are missing downstreams
    thrown.expect(ResourceConflictException.class);
    thrown.expectMessage("Failed to submit 1 change due to the following problems:\n" + 
        "Change 1: Missing downstream branches for [ds_one]. Please recreate the automerges.");
    gApi.changes().id(result.getChangeId()).current().submit();
  }
}
