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
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** Class to read the config and swap it out of memory if the config has changed. */
@Singleton
public class ConfigLoader {
  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
  public final String configProject;
  public final String configProjectBranch;
  public final String configFilename;
  public final List<String> configOptionKeys;

  protected GerritApi gApi;
  private volatile LoadedConfig config;

  /**
   * Read static configuration from config_keys.yaml and try to load initial dynamic configuration.
   *
   * <p>If loading dynamic configuration fails, logs and treats configuration as empty. Callers can
   * call {@link loadConfig} to retry.
   *
   * @param gApi API to access gerrit information.
   * @throws IOException if reading config_keys.yaml failed
   */
  @Inject
  public ConfigLoader(GerritApi gApi) throws IOException, RestApiException {
    this.gApi = gApi;

    String configKeysPath = "/config/config_keys.yaml";
    try (InputStreamReader streamReader =
        new InputStreamReader(getClass().getResourceAsStream(configKeysPath), Charsets.UTF_8)) {

      String automergerConfigYamlString = CharStreams.toString(streamReader);
      Map<String, Object> automergerConfig =
          (Map<String, Object>) (new Yaml().load(automergerConfigYamlString));
      configProject = (String) automergerConfig.get("config_project");
      configProjectBranch = (String) automergerConfig.get("config_project_branch");
      configFilename = (String) automergerConfig.get("config_filename");
      configOptionKeys = (List<String>) automergerConfig.get("config_option_keys");

      loadConfig();
    }
  }

  /**
   * Swap out the current config for a new, up to date config.
   *
   * @throws IOException
   * @throws RestApiException
   */
  public void loadConfig() throws IOException, RestApiException {
    config =
        new LoadedConfig(
            gApi, configProject, configProjectBranch, configFilename, configOptionKeys);
  }

  /**
   * Detects whether to skip a change based on the configuration. ( )
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @param commitMessage Commit message of the change.
   * @return True if we match blank_merge_regex and merge_all is false, or we match
   *     always_blank_merge_regex
   */
  public boolean isSkipMerge(String fromBranch, String toBranch, String commitMessage) {
    return config.isSkipMerge(fromBranch, toBranch, commitMessage);
  }

  /**
   * Get the merge configuration for a pair of branches.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @return The configuration for the given input.
   */
  public Map<String, Object> getConfig(String fromBranch, String toBranch) {
    return config.getMergeConfig(fromBranch, toBranch);
  }

  /**
   * Returns the name of the automerge label (i.e. the label to vote -1 if we have a merge conflict)
   *
   * @return Returns the name of the automerge label.
   */
  public String getAutomergeLabel() {
    return config.getAutomergeLabel();
  }

  /**
   * Returns a string to append to the end of the merge conflict message.
   *
   * @return The message string, or the empty string if nothing is specified.
   */
  public String getExtraConflictMessage() {
    return config.getExtraConflictMessage();
  }

  /**
   * Get the projects that should be merged for the given pair of branches.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @return The projects that are in scope of the given projects.
   * @throws RestApiException
   * @throws IOException
   */
  public Set<String> getProjectsInScope(String fromBranch, String toBranch)
      throws RestApiException, IOException {
    try {
      Set<String> projectSet = new HashSet<String>();

      Set<String> fromProjectSet = getManifestProjects(fromBranch);
      projectSet.addAll(fromProjectSet);

      Set<String> toProjectSet = getManifestProjects(fromBranch, toBranch);
      // Take intersection of project sets, unless one is empty.
      if (projectSet.isEmpty()) {
        projectSet = toProjectSet;
      } else if (!toProjectSet.isEmpty()) {
        projectSet.retainAll(toProjectSet);
      }

      // The lower the level a config is applied, the higher priority it has
      // For example, a project ignored in the global config but added in the branch config will
      // be added to the final project set, not ignored
      applyConfig(projectSet, config.getGlobal());
      applyConfig(projectSet, config.getMergeConfig(fromBranch));
      applyConfig(projectSet, config.getMergeConfig(fromBranch, toBranch));

      log.debug("Project set for {} to {} is {}", fromBranch, toBranch, projectSet);
      return projectSet;
    } catch (RestApiException | IOException e) {
      log.error("Error reading manifest for {}!", fromBranch, e);
      throw e;
    }
  }

  /**
   * Gets the downstream branches of the given branch and project.
   *
   * @param fromBranch The branch we are merging from.
   * @param project The project we are merging.
   * @return The branches downstream of the given branch for the given project.
   * @throws RestApiException
   * @throws IOException
   */
  public Set<String> getDownstreamBranches(String fromBranch, String project)
      throws RestApiException, IOException {
    Set<String> downstreamBranches = new HashSet<String>();
    Map<String, Object> fromBranchConfig = config.getMergeConfig(fromBranch);

    if (fromBranchConfig != null) {
      for (String key : fromBranchConfig.keySet()) {
        if (!configOptionKeys.contains(key)) {
          // If it's not a config option, then the key is the toBranch
          Set<String> projectsInScope = getProjectsInScope(fromBranch, key);
          if (projectsInScope.contains(project)) {
            downstreamBranches.add(key);
          }
        }
      }
    }
    return downstreamBranches;
  }

  // Returns overriden manifest config if specified, default if not
  private Map<String, String> getManifestInfoFromConfig(Map<String, Object> configMap) {
    if (configMap.containsKey("manifest")) {
      return (Map<String, String>) configMap.get("manifest");
    }
    return config.getDefaultManifestInfo();
  }

  // Returns contents of manifest file for the given branch.
  // If manifest does not exist, return empty set.
  private Set<String> getManifestProjects(String fromBranch) throws RestApiException, IOException {
    Map<String, Object> fromBranchConfig = config.getMergeConfig(fromBranch);
    if (fromBranchConfig == null) {
      return new HashSet<>();
    }
    Map<String, String> manifestProjectInfo = getManifestInfoFromConfig(fromBranchConfig);
    return getManifestProjectsForBranch(manifestProjectInfo, fromBranch);
  }

  // Returns contents of manifest file for the given branch pair
  // If manifest does not exist, return empty set.
  private Set<String> getManifestProjects(String fromBranch, String toBranch)
      throws RestApiException, IOException {
    Map<String, Object> toBranchConfig = config.getMergeConfig(fromBranch, toBranch);
    if (toBranchConfig == null) {
      return new HashSet<>();
    }
    Map<String, String> manifestProjectInfo = getManifestInfoFromConfig(toBranchConfig);
    return getManifestProjectsForBranch(manifestProjectInfo, toBranch);
  }

  private Set<String> getManifestProjectsForBranch(
      Map<String, String> manifestProjectInfo, String branch) throws RestApiException, IOException {
    String manifestProject = manifestProjectInfo.get("project");
    String manifestFile = manifestProjectInfo.get("file");
    try {
      BinaryResult manifestConfig =
          gApi.projects().name(manifestProject).branch(branch).file(manifestFile);
      ManifestReader manifestReader = new ManifestReader(branch, manifestConfig.asString());
      return manifestReader.getProjects();
    } catch (ResourceNotFoundException e) {
      log.debug("Manifest for {} not found", branch);
      return new HashSet<>();
    }
  }

  private void applyConfig(Set<String> projects, Map<String, Object> givenConfig) {
    if (givenConfig == null) {
      return;
    }
    if (givenConfig.containsKey("set_projects")) {
      List<String> setProjects = (ArrayList<String>) givenConfig.get("set_projects");
      projects.clear();
      projects.addAll(setProjects);
      // if we set projects we can ignore the rest
      return;
    }
    if (givenConfig.containsKey("add_projects")) {
      List<String> addProjects = (List<String>) givenConfig.get("add_projects");
      projects.addAll(addProjects);
    }
    if (givenConfig.containsKey("ignore_projects")) {
      List<String> ignoreProjects = (List<String>) givenConfig.get("ignore_projects");
      projects.removeAll(ignoreProjects);
    }
  }
}
