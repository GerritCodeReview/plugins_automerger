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

import com.google.common.base.Joiner;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.re2j.Pattern;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class to read the config. */
@Singleton
public class ConfigLoader {
  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

  private final GerritApi gApi;
  private final String pluginName;
  private Config cfg;
  private final Pattern blankMergePattern;
  private final Pattern alwaysBlankMergePattern;
  private final String branchDelimiter = "..";

  /**
   * Read config from plugin project.
   *
   * @param gApi API to access gerrit information.
   * @throws NoSuchProjectException if no All-Projects exists
   */
  @Inject
  public ConfigLoader(
      GerritApi gApi,
      AllProjectsName allProjectsName,
      @PluginName String pluginName,
      PluginConfigFactory cfgFactory)
      throws NoSuchProjectException {
    this.gApi = gApi;
    this.pluginName = pluginName;
    this.cfg = cfgFactory.getProjectPluginConfig(allProjectsName, pluginName);

    blankMergePattern = getConfigPattern("blankMerge");
    alwaysBlankMergePattern = getConfigPattern("alwaysBlankMerge");
  }

  private Pattern getConfigPattern(String key) {
    String[] patternList = cfg.getStringList("global", null, key);
    if (patternList != null) {
      Set<String> mergeStrings = new HashSet<String>(Arrays.asList(patternList));
      return Pattern.compile(Joiner.on("|").join(mergeStrings), Pattern.DOTALL);
    }
    return null;
  }

  /**
   * Detects whether to skip a change based on the configuration.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @param commitMessage Commit message of the change.
   * @return True if we match blank_merge_regex and merge_all is false, or we match
   *     always_blank_merge_regex
   */
  public boolean isSkipMerge(String fromBranch, String toBranch, String commitMessage) {
    if (alwaysBlankMergePattern != null && alwaysBlankMergePattern.matches(commitMessage)) {
      return true;
    }

    // If regex matches blank_merge (DO NOT MERGE), skip iff merge_all is false
    if (blankMergePattern != null && blankMergePattern.matches(commitMessage)) {
      return !getMergeAll(fromBranch, toBranch);
    }
    return false;
  }

  private boolean getMergeAll(String fromBranch, String toBranch) {
    return cfg.getBoolean("automerger", fromBranch + branchDelimiter + toBranch, "mergeAll", false);
  }

  /**
   * Returns the name of the automerge label (i.e. the label to vote -1 if we have a merge conflict)
   *
   * @throws IOException
   * @throws RestApiException
   * @return Returns the name of the automerge label.
   */
  public String getAutomergeLabel() throws IOException, RestApiException {
    String automergeLabel = cfg.getString("global", null, "automergeLabel");
    if (automergeLabel == null) {
      return "Verified";
    }
    return automergeLabel;
  }

  /**
   * Get the projects that should be merged for the given pair of branches.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @return The projects that are in scope of the given projects.
   * @throws RestApiException
   * @throws IOException
   * @throws ConfigInvalidException
   */
  public Set<String> getProjectsInScope(String fromBranch, String toBranch)
      throws RestApiException, IOException, ConfigInvalidException {
    try {
      Set<String> projectSet = getManifestProjects(fromBranch, toBranch);
      applyConfig(fromBranch, toBranch, projectSet);

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
   * @throws ConfigInvalidException
   */
  public Set<String> getDownstreamBranches(String fromBranch, String project)
      throws RestApiException, ConfigInvalidException, IOException {
    Set<String> downstreamBranches = new HashSet<String>();
    // List all subsections of automerger, split by ..
    log.info("Plugin name: {}", pluginName);
    Set<String> subsections = cfg.getSubsections(pluginName);
    for (String subsection : subsections) {
      // Subsections are of the form "fromBranch..toBranch"
      String[] branchPair = subsection.split(Pattern.quote(branchDelimiter));
      if (branchPair.length != 2) {
        log.error("Branch pair {} was not split by {}", subsection, branchDelimiter);
        throw new ConfigInvalidException("Branch pair malformed: " + subsection);
      }
      log.info("From branch {} vs branchPair[0] {}", fromBranch, branchPair[0]);
      if (fromBranch.equals(branchPair[0])) {
        // If fromBranches match, check if project is in both their manifests
        Set<String> projectsInScope = getProjectsInScope(branchPair[0], branchPair[1]);
        log.info("Projects in scope {}", projectsInScope);
        if (projectsInScope.contains(project)) {
          downstreamBranches.add(branchPair[1]);
        }
      }
    }
    return downstreamBranches;
  }

  // Returns overriden manifest config if specified, default if not
  private String getManifestFile() throws ConfigInvalidException {
    String manifestFile = cfg.getString("global", null, "manifestFile");
    if (manifestFile == null) {
      throw new ConfigInvalidException("manifestFile not specified.");
    }
    return manifestFile;
  }

  // Returns overriden manifest config if specified, default if not
  private String getManifestProject() throws ConfigInvalidException {
    String manifestProject = cfg.getString("global", null, "manifestProject");
    if (manifestProject == null) {
      throw new ConfigInvalidException("manifestProject not specified.");
    }
    return manifestProject;
  }

  // Returns contents of manifest file for the given branch pair
  // If manifest does not exist, return empty set.
  private Set<String> getManifestProjects(String fromBranch, String toBranch)
      throws RestApiException, IOException, ConfigInvalidException {
    boolean ignoreSourceManifest =
        cfg.getBoolean(
            "automerger", fromBranch + branchDelimiter + toBranch, "ignoreSourceManifest", false);
    Set<String> fromProjects = new HashSet<>();
    if (!ignoreSourceManifest) {
      fromProjects = getProjectsInManifest(getManifestProject(), getManifestFile(), fromBranch);
    }
    Set<String> toProjects =
        getProjectsInManifest(getManifestProject(), getManifestFile(), toBranch);
    toProjects.retainAll(fromProjects);
    return toProjects;
  }

  private Set<String> getProjectsInManifest(
      String manifestProject, String manifestFile, String branch)
      throws RestApiException, IOException {
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

  private void applyConfig(String fromBranch, String toBranch, Set<String> projects) {
    List<String> setProjects =
        Arrays.asList(
            cfg.getStringList(
                "automerger", fromBranch + branchDelimiter + toBranch, "setProjects"));
    if (!setProjects.isEmpty()) {
      projects.clear();
      projects.addAll(setProjects);
      // if we set projects we can ignore the rest
      return;
    }
    List<String> addProjects =
        Arrays.asList(
            cfg.getStringList(
                "automerger", fromBranch + branchDelimiter + toBranch, "addProjects"));
    projects.addAll(addProjects);
    List<String> ignoreProjects =
        Arrays.asList(
            cfg.getStringList(
                "automerger", fromBranch + branchDelimiter + toBranch, "ignoreProjects"));
    projects.removeAll(ignoreProjects);
  }
}
