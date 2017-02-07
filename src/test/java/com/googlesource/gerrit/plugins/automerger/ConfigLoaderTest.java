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

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.project.ProjectCache;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

public class ConfigLoaderTest {
  protected GerritApi gApiMock;
  private ProjectCache projectCacheMock;
  private ConfigLoader configLoader;
  private String configString;
  private String manifestString;
  private String firstDownstreamManifestString;
  private String secondDownstreamManifestString;

  @Before
  public void setUp() throws Exception {
    gApiMock = Mockito.mock(GerritApi.class, Mockito.RETURNS_DEEP_STUBS);
    projectCacheMock = Mockito.mock(ProjectCache.class, Mockito.RETURNS_DEEP_STUBS);
    mockFile(
        "automerger_config.yaml", "All-Projects", "refs/meta/config", "automerger_config.yaml");
    mockFile("default.xml", "platform/manifest", "master", "default.xml");
    mockFile("ds_one.xml", "platform/manifest", "ds_one", "default.xml");
    mockFile("ds_two.xml", "platform/manifest", "ds_two", "default.xml");
  }

  private void mockFile(String resourceName, String projectName, String branchName, String filename)
      throws Exception {
    try (InputStream in = getClass().getResourceAsStream(resourceName)) {
      String resourceString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));
      Mockito.when(
              gApiMock.projects().name(projectName).branch(branchName).file(filename).asString())
          .thenReturn(resourceString);
    }
  }

  private void loadConfig() throws Exception {
    configLoader = new ConfigLoader(gApiMock, projectCacheMock);
  }

  @Test
  public void getProjectsInScopeTest_addProjects() throws Exception {
    loadConfig();
    Set<String> expectedProjects = new HashSet<String>();
    expectedProjects.add("platform/whee");
    expectedProjects.add("platform/added/project");
    assertThat(configLoader.getProjectsInScope("master", "ds_one")).isEqualTo(expectedProjects);
  }

  @Test
  public void getProjectsInScopeTest_setProjects() throws Exception {
    loadConfig();
    Set<String> otherExpectedProjects = new HashSet<String>();
    otherExpectedProjects.add("platform/some/project");
    otherExpectedProjects.add("platform/other/project");
    assertThat(configLoader.getProjectsInScope("master", "ds_two"))
        .isEqualTo(otherExpectedProjects);
  }

  @Test
  public void isSkipMergeTest_noSkip() throws Exception {
    loadConfig();
    assertThat(configLoader.isSkipMerge("ds_two", "ds_three", "bla")).isFalse();
  }

  @Test
  public void isSkipMergeTest_blankMerge() throws Exception {
    loadConfig();
    assertThat(configLoader.isSkipMerge("ds_two", "ds_three", "test test \n \n DO NOT MERGE lala"))
        .isTrue();
  }

  @Test
  public void isSkipMergeTest_blankMergeWithMergeAll() throws Exception {
    loadConfig();
    assertThat(configLoader.isSkipMerge("master", "ds_two", "test test \n \n DO NOT MERGE"))
        .isFalse();
  }

  @Test
  public void isSkipMergeTest_alwaysBlankMerge() throws Exception {
    loadConfig();
    assertThat(
            configLoader.isSkipMerge("master", "ds_one", "test test \n \n DO NOT MERGE ANYWHERE"))
        .isTrue();
  }

  @Test
  public void downstreamBranchesTest() throws Exception {
    loadConfig();
    Set<String> expectedBranches = new HashSet<String>();
    expectedBranches.add("ds_two");
    assertThat(configLoader.getDownstreamBranches("master", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  @Test
  public void downstreamBranchesTest_nonexistentBranch() throws Exception {
    loadConfig();
    Set<String> expectedBranches = new HashSet<String>();
    assertThat(configLoader.getDownstreamBranches("idontexist", "platform/some/project"))
        .isEqualTo(expectedBranches);
  }

  @Test(expected = IOException.class)
  public void downstreamBranchesTest_IOException() throws Exception {
    Mockito.when(
            gApiMock
                .projects()
                .name("platform/manifest")
                .branch("master")
                .file("default.xml")
                .asString())
        .thenThrow(new IOException("!"));
    loadConfig();
    Set<String> expectedBranches = new HashSet<String>();

    configLoader.getDownstreamBranches("master", "platform/some/project");
  }

  @Test(expected = RestApiException.class)
  public void downstreamBranchesTest_restApiException() throws Exception {
    Mockito.when(gApiMock.projects().name("platform/manifest").branch("master"))
        .thenThrow(new RestApiException("!"));
    loadConfig();
    configLoader.getDownstreamBranches("master", "platform/some/project");
  }
}
