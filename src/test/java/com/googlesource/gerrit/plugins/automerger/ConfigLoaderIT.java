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

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

@TestPlugin(
    name = "automerger",
    sysModule = "com.googlesource.gerrit.plugins.automerger.AutomergerModule")
public class ConfigLoaderIT extends LightweightPluginDaemonTest {
  private ConfigLoader configLoader;
  @Inject private AllProjectsName allProjectsName;
  @Inject private PluginConfigFactory cfgFactory;
  @Inject @CanonicalWebUrl String canonicalGerritWebUrl;
  @Inject private Provider<CurrentUser> currentUser;
  @Inject private ProjectOperations projectOperations;
  private Project.NameKey manifestNameKey;

  @Test
  public void getProjectsInScopeTest_addProjects() throws Exception {
    defaultSetup("automerger.config");
    Set<String> expectedProjects = new HashSet<>();
    expectedProjects.add("platform/whee");
    expectedProjects.add("platform/added/project");
    assertThat(configLoader.getProjectsInScope("master", "ds_one")).isEqualTo(expectedProjects);
  }

  @Test
  public void getProjectsInScopeTest_setProjects() throws Exception {
    defaultSetup("automerger.config");
    Set<String> otherExpectedProjects = new HashSet<>();
    otherExpectedProjects.add("platform/some/project");
    otherExpectedProjects.add("platform/other/project");
    otherExpectedProjects.add("platform/added/project");
    assertThat(configLoader.getProjectsInScope("master", "ds_two"))
        .isEqualTo(otherExpectedProjects);
  }

  @Test
  public void getProjectsInScope_missingSourceManifest() throws Exception {
    projectOperations.newProject().name(name("All-Projects")).create();
    manifestNameKey = projectOperations.newProject().name(name("platform/manifest")).create();
    setupTestRepo("ds_one.xml", manifestNameKey, "ds_one", "default.xml");
    setupTestRepo("ds_two.xml", manifestNameKey, "ds_two", "default.xml");
    loadConfig("alternate.config");
    assertThat(configLoader.getProjectsInScope("master", "ds_one").isEmpty()).isTrue();
  }

  @Test
  public void getProjectsInScope_ignoreSourceManifest() throws Exception {
    defaultSetup("alternate.config");
    Set<String> expectedProjects = new HashSet<>();
    expectedProjects.add("platform/whee");
    expectedProjects.add("whuu");
    assertThat(configLoader.getProjectsInScope("master", "ds_two")).isEqualTo(expectedProjects);
  }

  @Test
  public void getProjectsInScope_ignoreSourceManifestWithMissingDestManifest() throws Exception {
    defaultSetup("alternate.config");
    assertThat(configLoader.getProjectsInScope("master", "ds_four").isEmpty()).isTrue();
  }

  @Test
  public void isSkipMergeTest_noSkip() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.isSkipMerge("ds_two", "ds_three", "bla")).isFalse();
  }

  @Test
  public void isSkipMergeTest_blankMerge() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.isSkipMerge("ds_two", "ds_three", "test test \n \n DO NOT MERGE lala"))
        .isTrue();
  }

  @Test
  public void isSkipMergeTest_blankMergeWithMergeAll() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.isSkipMerge("master", "ds_two", "test test \n \n DO NOT MERGE"))
        .isFalse();
  }

  @Test
  public void isSkipMergeTest_alwaysBlankMerge() throws Exception {
    defaultSetup("automerger.config");
    assertThat(
            configLoader.isSkipMerge("master", "ds_one", "test test \n \n DO NOT MERGE ANYWHERE"))
        .isTrue();
  }

  @Test
  public void isSkipMergeTest_alwaysBlankMergeDummy() throws Exception {
    defaultSetup("alternate.config");
    assertThat(configLoader.isSkipMerge("master", "ds_two", "test test")).isFalse();
  }

  @Test
  public void isSkipMergeTest_alwaysBlankMergeNull() throws Exception {
    defaultSetup("alternate.config");
    assertThat(configLoader.isSkipMerge("master", "ds_two", "test test \n \n BLANK ANYWHERE"))
        .isTrue();
  }

  @Test
  public void isSkipMergeTest_noBlankMergeSpecified() throws Exception {
    defaultSetup("empty_blank.config");
    assertThat(configLoader.isSkipMerge("master", "ds_one", "test test \n \n DO NOT MERGE"))
        .isFalse();
  }

  @Test
  public void upstreamBranchesTest() throws Exception {
    defaultSetup("automerger.config");
    Set<String> expectedBranches = new HashSet<>();
    expectedBranches.add("master");
    assertThat(configLoader.getUpstreamBranches("ds_two", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  @Test
  public void upstreamBranchesTest_nonexistentBranch() throws Exception {
    defaultSetup("automerger.config");
    Set<String> expectedBranches = new HashSet<>();
    assertThat(configLoader.getUpstreamBranches("master", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  @Test
  public void downstreamBranchesTest() throws Exception {
    defaultSetup("automerger.config");
    Set<String> expectedBranches = new HashSet<>();
    expectedBranches.add("ds_two");
    assertThat(configLoader.getDownstreamBranches("master", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  @Test
  public void downstreamBranchesTest_nonexistentBranch() throws Exception {
    defaultSetup("automerger.config");
    Set<String> expectedBranches = new HashSet<>();
    assertThat(configLoader.getDownstreamBranches("idontexist", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  @Test
  public void downstreamBranchesTest_configException() throws Exception {
    defaultSetup("wrong.config");

    ConfigInvalidException thrown =
        assertThrows(
            ConfigInvalidException.class,
            () -> configLoader.getDownstreamBranches("master", "platform/some/project"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Automerger config branch pair malformed: master..ds_one");
  }

  @Test
  public void getAllDownstreamBranchesTest() throws Exception {
    defaultSetup("automerger.config");
    Set<String> expectedBranches = new HashSet<>();
    expectedBranches.add("ds_two");
    expectedBranches.add("ds_three");
    assertThat(configLoader.getAllDownstreamBranches("master", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  private void defaultSetup(String resourceName) throws Exception {
    projectOperations.newProject().name(name("All-Projects")).create();
    manifestNameKey = projectOperations.newProject().name(name(("platform/manifest"))).create();
    setupTestRepo("default.xml", manifestNameKey, "master", "default.xml");
    setupTestRepo("ds_one.xml", manifestNameKey, "ds_one", "default.xml");
    setupTestRepo("ds_two.xml", manifestNameKey, "ds_two", "default.xml");
    loadConfig(resourceName);
  }

  @Test
  public void getDefaultConflictMessageTest() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.getConflictMessage()).isEqualTo("Merge conflict found on ${branch}");
  }

  @Test
  public void getMultilineConflictMessageTest() throws Exception {
    defaultSetup("alternate.config");
    assertThat(configLoader.getConflictMessage())
        .isEqualTo("line1\n" + "line2\n" + "line3 ${branch}\n" + "line4");
  }

  @Test
  public void getDefaultManifestConflictMessageTest() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.getManifestConflictMessage())
        .isEqualTo("Merge conflict found on ${branch}");
  }

  @Test
  public void getMultilineManifestConflictMessageTest() throws Exception {
    defaultSetup("alternate.config");
    assertThat(configLoader.getManifestConflictMessage())
        .isEqualTo("mline1\n" + "mline2\n" + "mline3 ${branch}\n" + "mline4");
  }

  @Test
  public void getMinAutomergeVoteTest() throws Exception {
    defaultSetup("alternate.config");
    assertThat(configLoader.getMinAutomergeVote()).isEqualTo(-3);
  }

  @Test
  public void minAutomergeVoteDisabledTest() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.minAutomergeVoteDisabled()).isFalse();
  }

  @Test
  public void getContextUserIdTest() throws Exception {
    defaultSetup("context_user.config");
    assertThat(configLoader.getContextUserId()).isEqualTo(Account.id(102304));
  }

  @Test
  public void getContextUserIdTest_noContextUser() throws Exception {
    defaultSetup("automerger.config");
    assertThat(configLoader.getContextUserId()).isEqualTo(currentUser.get().getAccountId());
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

  private void pushConfig(String resourceName) throws Exception {
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));

      Config cfg = new Config();
      cfg.fromText(resourceString);
      // Update manifest project path to the result of createProject(resourceName), since it is
      // scoped to the test method
      cfg.setString("global", null, "manifestProject", manifestNameKey.get());
      PushOneCommit push =
          pushFactory.create(
              admin.newIdent(), allProjectRepo, "Subject", "automerger.config", cfg.toText());
      push.to("refs/meta/config").assertOkStatus();
    }
  }

  private void loadConfig(String configFilename) throws Exception {
    pushConfig(configFilename);
    configLoader =
        new ConfigLoader(
            gApi, allProjectsName, "automerger", canonicalGerritWebUrl, cfgFactory, currentUser);
  }
}
