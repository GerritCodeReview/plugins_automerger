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
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.ProjectCache;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ConfigLoaderTest {
  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private GerritApi gApiMock;
  private ConfigLoader configLoader;
  private AllProjectsName allProjectsName;
  private PluginConfigFactory cfgFactory;
  private Config cfg;

  @Before
  public void setUp() throws Exception {
    allProjectsName = new AllProjectsName("All-Projects");
    mockFile("automerger.config", "All-Projects", "refs/meta/config", "automerger.config");
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
    cfg = new Config();
    cfg.fromText(
        gApiMock
            .projects()
            .name("All-Projects")
            .branch("refs/meta/config")
            .file("automerger.config")
            .asString());
    cfgFactory = Mockito.mock(PluginConfigFactory.class);
    Mockito.when(cfgFactory.getProjectPluginConfig(allProjectsName, "automerger")).thenReturn(cfg);
    configLoader = new ConfigLoader(gApiMock, allProjectsName, "automerger", cfgFactory);
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
    public void isSkipMergeTest_alwaysBlankMergeDummy() throws Exception {
      mockFile("alternate.config", "All-Projects", "refs/meta/config", "automerger.config");
      loadConfig();
      assertThat(configLoader.isSkipMerge("master", "ds_two", "test test")).isFalse();
    }
  
    @Test
    public void isSkipMergeTest_alwaysBlankMergeNull() throws Exception {
      mockFile("alternate.config", "All-Projects", "refs/meta/config", "automerger.config");
      loadConfig();
      assertThat(
              configLoader.isSkipMerge("master", "ds_two", "test test \n \n BLANK ANYWHERE"))
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
  
    @Test(expected = ConfigInvalidException.class)
    public void downstreamBranchesTest_configException() throws Exception {
      mockFile("wrong.config", "All-Projects", "refs/meta/config", "automerger.config");
      loadConfig();
  
      configLoader.getDownstreamBranches("master", "platform/some/project");
    }
    
    @Test
    public void getManifestProjects_ignoreSourceManifest() throws Exception {
      
    }
}
