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
import com.google.common.io.Files;
import com.google.gerrit.acceptance.PluginDaemonTest;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.projects.Projects;
import com.google.gerrit.extensions.api.projects.ProjectApi;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.restapi.BinaryResult;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import java.nio.file.Paths;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class ConfigLoaderIT extends PluginDaemonTest {

  private GerritApi gApiMock;
  private Projects projectMock;
  private ProjectApi projectApiMock;
  private BranchApi branchApiMock;
  private BinaryResult binaryResultMock;
  private ConfigLoader configLoader;
  String configString;
  String manifestString;
  String firstDownstreamManifestString;
  String secondDownstreamManifestString;

  @Before
  public void setUp() throws Exception {
    gApiMock = createMock(GerritApi.class);
    projectMock = EasyMock.createMock(Projects.class);
    projectApiMock = EasyMock.createMock(ProjectApi.class);
    branchApiMock = EasyMock.createMock(BranchApi.class);
    binaryResultMock = EasyMock.createMock(BinaryResult.class);
    configLoader = new ConfigLoader(gApiMock);

    String configFilePath = Paths.get(".", "plugins", "automerger-plugin", "src", "test",
        "resources", "com", "googlesource", "gerrit", "plugins", "automerger",
        "test.yaml").toAbsolutePath().normalize().toString();
    File configFile = new File(configFilePath);
    configString = Files.toString(configFile, Charsets.UTF_8);

    String manifestPath = Paths.get(".", "plugins", "automerger-plugin", "src", "test",
        "resources", "com", "googlesource", "gerrit", "plugins", "automerger",
        "default.xml").toAbsolutePath().normalize().toString();
    File manifestFile = new File(manifestPath);
    manifestString = Files.toString(manifestFile, Charsets.UTF_8);

    String firstDownstreamManifestPath = Paths.get(".", "plugins", "automerger-plugin", "src", "test",
        "resources", "com", "googlesource", "gerrit", "plugins", "automerger",
        "ds_one.xml").toAbsolutePath().normalize().toString();
    File firstDownstreamManifestFile = new File(firstDownstreamManifestPath);
    firstDownstreamManifestString = Files.toString(firstDownstreamManifestFile, Charsets.UTF_8);

    String secondDownstreamManifestPath = Paths.get(".", "plugins", "automerger-plugin", "src", "test",
        "resources", "com", "googlesource", "gerrit", "plugins", "automerger",
        "ds_two.xml").toAbsolutePath().normalize().toString();
    File secondDownstreamManifestFile = new File(secondDownstreamManifestPath);
    secondDownstreamManifestString = Files.toString(secondDownstreamManifestFile, Charsets.UTF_8);
  }

  private void loadMockConfig() throws Exception {
    expect(gApiMock.projects()).andReturn(projectMock).once();
    expect(projectMock.name("tools/automerger")).andReturn(projectApiMock).once();
    expect(projectApiMock.branch("master")).andReturn(branchApiMock).once();
    expect(branchApiMock.file("test.yaml")).andReturn(binaryResultMock).once();
    expect(binaryResultMock.asString()).andReturn(configString).once();
  }

  private void loadMockManifest(String from_branch, String fromManifestString,
                                String to_branch, String toManifestString) throws Exception {
    expect(gApiMock.projects()).andReturn(projectMock).times(2);
    expect(projectMock.name("platform/manifest")).andReturn(projectApiMock).times(2);

    expect(projectApiMock.branch(from_branch)).andReturn(branchApiMock).once();
    expect(branchApiMock.file("default.xml")).andReturn(binaryResultMock).once();
    expect(binaryResultMock.asString()).andReturn(fromManifestString).once();

    expect(projectApiMock.branch(to_branch)).andReturn(branchApiMock).once();
    expect(branchApiMock.file("default.xml")).andReturn(binaryResultMock).once();
    expect(binaryResultMock.asString()).andReturn(toManifestString).once();
  }

  private void replay() throws Exception {
    EasyMock.replay(binaryResultMock);
    EasyMock.replay(branchApiMock);
    EasyMock.replay(projectApiMock);
    EasyMock.replay(projectMock);
    EasyMock.replay(gApiMock);
    configLoader.loadConfig();
  }

  @Test
  public void getProjectsInScopeTest_addProjects() throws Exception {
    loadMockConfig();
    loadMockManifest("master", manifestString, "ds_one", firstDownstreamManifestString);
    replay();

    Set<String> expectedProjects = new HashSet<String>();
    expectedProjects.add("platform/whee");
    expectedProjects.add("platform/added/project");
    assertThat(configLoader.getProjectsInScope("master", "ds_one")).isEqualTo(expectedProjects);
  }

  @Test
  public void getProjectsInScopeTest_setProjects() throws Exception {
    loadMockConfig();
    loadMockManifest("master", manifestString, "ds_two", secondDownstreamManifestString);
    replay();

    Set<String> otherExpectedProjects = new HashSet<String>();
    otherExpectedProjects.add("platform/some/project");
    otherExpectedProjects.add("platform/other/project");
    assertThat(configLoader.getProjectsInScope("master", "ds_two")).isEqualTo(otherExpectedProjects);
  }

  @Test
  public void downstreamBranchesTest() throws Exception {
    loadMockConfig();
    loadMockManifest("master", manifestString, "ds_one", secondDownstreamManifestString);
    loadMockManifest("master", manifestString, "ds_two", secondDownstreamManifestString);
    loadMockManifest("ds_two", manifestString, "ds_three", secondDownstreamManifestString);
    replay();

    Set<String> expectedBranches = new HashSet<String>();
    expectedBranches.add("ds_two");
    expectedBranches.add("ds_three");

    assertThat(configLoader.getDownstreamBranches(
        "master", "platform/some/project", false)).isEqualTo(expectedBranches);
  }

  @Test
  public void downstreamBranchesTest_nonexistentBranch() throws Exception {
    loadMockConfig();
    replay();

    Set<String> expectedBranches = new HashSet<String>();

    assertThat(configLoader.getDownstreamBranches(
        "idontexist", "platform/some/project", false)).isEqualTo(expectedBranches);
  }
  // TODO(stephenli): test for error case i.e. restapiexception, resourcenotfound thrown
  // TODO(stephenli): test some DO NOT MERGEs (i.e. merge_all is true)
}
