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
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.CharStreams;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
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
  private final ProjectCache projectCache;
  private LoadingCache<String, LoadedConfig> loadedConfigCache;

  /**
   * Read static configuration from config_keys.yaml and try to load initial dynamic configuration.
   *
   * <p>
   * If loading dynamic configuration fails, logs and treats configuration as empty. Callers can
   * call {@link loadConfig} to retry.
   *
   * @param gApi API to access gerrit information.
   * @throws IOException if reading config_keys.yaml failed
   */
  @Inject
  public ConfigLoader(GerritApi gApi, ProjectCache projectCache, AllProjectsName allProjectsName)
      throws IOException, RestApiException {
    this.gApi = gApi;
    this.projectCache = projectCache;
    this.configProject = allProjectsName.get();
    this.configProjectBranch = RefNames.REFS_CONFIG;

    String configKeysPath = "/config/config_keys.yaml";
    try (InputStreamReader streamReader =
        new InputStreamReader(getClass().getResourceAsStream(configKeysPath), Charsets.UTF_8)) {

      String automergerConfigYamlString = CharStreams.toString(streamReader);
      Map<String, Object> automergerConfig =
          (Map<String, Object>) (new Yaml().load(automergerConfigYamlString));
      configFilename = (String) automergerConfig.get("config_filename");
      configOptionKeys = (List<String>) automergerConfig.get("config_option_keys");

      this.loadedConfigCache =
          CacheBuilder.newBuilder().maximumSize(1).build(new CacheLoader<String, LoadedConfig>() {
            @Override
            public LoadedConfig load(String key) throws IOException, RestApiException {
              return new LoadedConfig(gApi, configProject, configProjectBranch, configFilename,
                  configOptionKeys);
            }
          });
    }
  }

  /**
   * Get the latest, up-to-date config from the LoadingCache.
   *
   * @throws IOException
   * @throws RestApiException
   */
  public LoadedConfig getCurrentConfig() throws IOException, RestApiException {
    try {
      return loadedConfigCache.get(getCurrentRevision());
    } catch (ExecutionException e) {
      throw new IOException("Failed to get automerger config from cache.", e.getCause());  
    }
  }

  /**
   * Detects whether to skip a change based on the configuration. ( )
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @param commitMessage Commit message of the change.
   * @throws IOException
   * @throws RestApiException
   * @return True if we match blank_merge_regex and merge_all is false, or we match
   *         always_blank_merge_regex
   */
  public boolean isSkipMerge(String fromBranch, String toBranch, String commitMessage)
      throws IOException, RestApiException {
    LoadedConfig config = getCurrentConfig();
    return config.isSkipMerge(fromBranch, toBranch, commitMessage);
  }

  /**
   * Get the merge configuration for a pair of branches.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @throws IOException
   * @throws RestApiException
   * @return The configuration for the given input.
   */
  public Map<String, Object> getConfig(String fromBranch, String toBranch)
      throws IOException, RestApiException {
    LoadedConfig config = getCurrentConfig();
    return config.getMergeConfig(fromBranch, toBranch);
  }

  /**
   * Returns the name of the automerge label (i.e. the label to vote -1 if we have a merge conflict)
   *
   * @throws IOException
   * @throws RestApiException
   * @return Returns the name of the automerge label.
   */
  public String getAutomergeLabel() throws IOException, RestApiException {
    LoadedConfig config = getCurrentConfig();
    return config.getAutomergeLabel();
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
    LoadedConfig config = getCurrentConfig();
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
    LoadedConfig config = getCurrentConfig();
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

  private String getCurrentRevision() throws IOException {
    return projectCache.checkedGet(new Project.NameKey(configProject)).getConfig().getRevision()
        .toString();
  }

  // Returns overriden manifest config if specified, default if not
  private Map<String, String> getManifestInfoFromConfig(Map<String, Object> configMap)
      throws RestApiException, IOException {
    LoadedConfig config = getCurrentConfig();
    if (configMap.containsKey("manifest")) {
      return (Map<String, String>) configMap.get("manifest");
    }
    return config.getDefaultManifestInfo();
  }

  // Returns contents of manifest file for the given branch.
  // If manifest does not exist, return empty set.
  private Set<String> getManifestProjects(String fromBranch) throws RestApiException, IOException {
    LoadedConfig config = getCurrentConfig();
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
    LoadedConfig config = getCurrentConfig();
    Map<String, Object> toBranchConfig = config.getMergeConfig(fromBranch, toBranch);
    if (toBranchConfig == null) {
      return new HashSet<>();
    }
    Map<String, String> manifestProjectInfo = getManifestInfoFromConfig(toBranchConfig);
    return getManifestProjectsForBranch(manifestProjectInfo, toBranch);
  }

  private Set<String> getManifestProjectsForBranch(Map<String, String> manifestProjectInfo,
      String branch) throws RestApiException, IOException {
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
