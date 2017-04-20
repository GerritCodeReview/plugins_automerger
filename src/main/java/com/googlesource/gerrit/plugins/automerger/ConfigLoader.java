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
import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
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
  private static final String BRANCH_DELIMITER = ":";
  private static final String DEFAULT_CONFLICT_MESSAGE = "Merge conflict found on ${branch}";

  private final GerritApi gApi;
  private final String pluginName;
  private final String canonicalWebUrl;
  private final AllProjectsName allProjectsName;
  private final PluginConfigFactory cfgFactory;

  /**
   * Class to handle getting information from the config.
   *
   * @param gApi API to access gerrit information.
   * @param allProjectsName The name of the top-level project.
   * @param pluginName The name of the plugin we are running.
   * @param cfgFactory Factory to generate the plugin config.
   */
  @Inject
  public ConfigLoader(
      GerritApi gApi,
      AllProjectsName allProjectsName,
      @PluginName String pluginName,
      @CanonicalWebUrl String canonicalWebUrl,
      PluginConfigFactory cfgFactory) {
    this.gApi = gApi;
    this.canonicalWebUrl = canonicalWebUrl;
    this.pluginName = pluginName;
    this.cfgFactory = cfgFactory;
    this.allProjectsName = allProjectsName;
  }

  private Config getConfig() throws ConfigInvalidException {
    try {
      return cfgFactory.getProjectPluginConfig(allProjectsName, pluginName);
    } catch (NoSuchProjectException e) {
      throw new ConfigInvalidException(
          "Config invalid because " + allProjectsName.get() + " does not exist!");
    }
  }

  /**
   * Detects whether to skip a change based on the configuration.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @param commitMessage Commit message of the change.
   * @return True if we match blank_merge_regex and merge_all is false, or we match
   *     always_blank_merge_regex
   * @throws ConfigInvalidException
   */
  public boolean isSkipMerge(String fromBranch, String toBranch, String commitMessage)
      throws ConfigInvalidException {
    Pattern alwaysBlankMergePattern = getConfigPattern("alwaysBlankMerge");
    if (alwaysBlankMergePattern.matches(commitMessage)) {
      return true;
    }

    Pattern blankMergePattern = getConfigPattern("blankMerge");
    // If regex matches blank_merge (DO NOT MERGE), skip iff merge_all is false
    if (blankMergePattern.matches(commitMessage)) {
      return !getMergeAll(fromBranch, toBranch);
    }
    return false;
  }

  private Pattern getConfigPattern(String key) throws ConfigInvalidException {
    String[] patternList = getConfig().getStringList("global", null, key);
    Set<String> mergeStrings = new HashSet<>(Arrays.asList(patternList));
    return Pattern.compile(Joiner.on("|").join(mergeStrings), Pattern.DOTALL);
  }

  private boolean getMergeAll(String fromBranch, String toBranch) throws ConfigInvalidException {
    return getConfig()
        .getBoolean("automerger", fromBranch + BRANCH_DELIMITER + toBranch, "mergeAll", false);
  }

  /**
   * Returns the name of the automerge label (i.e. the label to vote -1 if we have a merge conflict)
   *
   * @return Returns the name of the automerge label.
   * @throws ConfigInvalidException
   */
  public String getAutomergeLabel() throws ConfigInvalidException {
    String automergeLabel = getConfig().getString("global", null, "automergeLabel");
    return automergeLabel != null ? automergeLabel : "Verified";
  }

  /**
   * Returns the hostName.
   *
   * <p>Uses the hostName defined in the configuration if specified. If not, defaults to the
   * canonicalWebUrl.
   *
   * @return Returns the hostname
   * @throws ConfigInvalidException
   */
  public String getHostName() throws ConfigInvalidException {
    String hostName = getConfig().getString("global", null, "hostName");
    return hostName != null ? hostName : canonicalWebUrl;
  }

  /**
   * Returns a string to append to the end of the merge conflict message.
   *
   * @return The message string, or the empty string if nothing is specified.
   * @throws ConfigInvalidException
   */
  public String getConflictMessage() throws ConfigInvalidException {
    String conflictMessage = getConfig().getString("global", null, "conflictMessage");
    if (Strings.isNullOrEmpty(conflictMessage)) {
      conflictMessage = DEFAULT_CONFLICT_MESSAGE;
    }
    return conflictMessage;
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
      projectSet = applyConfig(fromBranch, toBranch, projectSet);

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
      throws RestApiException, IOException, ConfigInvalidException {
    Set<String> downstreamBranches = new HashSet<String>();
    // List all subsections of automerger, split by :
    Set<String> subsections = getConfig().getSubsections(pluginName);
    for (String subsection : subsections) {
      // Subsections are of the form "fromBranch:toBranch"
      String[] branchPair = subsection.split(Pattern.quote(BRANCH_DELIMITER));
      if (branchPair.length != 2) {
        throw new ConfigInvalidException("Automerger config branch pair malformed: " + subsection);
      }
      if (fromBranch.equals(branchPair[0])) {
        // If fromBranches match, check if project is in both their manifests
        Set<String> projectsInScope = getProjectsInScope(branchPair[0], branchPair[1]);
        if (projectsInScope.contains(project)) {
          downstreamBranches.add(branchPair[1]);
        }
      }
    }
    return downstreamBranches;
  }

  public short getMaxAutomergeVote() throws ConfigInvalidException {
    return (short) getConfig().getInt("global", "maxAutomergeVote", 1);
  }

  public short getMinAutomergeVote() throws ConfigInvalidException {
    return (short) getConfig().getInt("global", "minAutomergeVote", 1);
  }

  // Returns overriden manifest config if specified, default if not
  private String getManifestFile() throws ConfigInvalidException {
    String manifestFile = getConfig().getString("global", null, "manifestFile");
    if (manifestFile == null) {
      throw new ConfigInvalidException("manifestFile not specified.");
    }
    return manifestFile;
  }

  // Returns overriden manifest config if specified, default if not
  private String getManifestProject() throws ConfigInvalidException {
    String manifestProject = getConfig().getString("global", null, "manifestProject");
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
        getConfig()
            .getBoolean(
                "automerger",
                fromBranch + BRANCH_DELIMITER + toBranch,
                "ignoreSourceManifest",
                false);

    Set<String> toProjects =
        getProjectsInManifest(getManifestProject(), getManifestFile(), toBranch);
    if (ignoreSourceManifest) {
      return toProjects;
    }

    Set<String> fromProjects =
        getProjectsInManifest(getManifestProject(), getManifestFile(), fromBranch);
    fromProjects.retainAll(toProjects);
    return fromProjects;
  }

  private Set<String> getProjectsInManifest(
      String manifestProject, String manifestFile, String branch)
      throws RestApiException, IOException {
    try (BinaryResult manifestConfig =
        gApi.projects().name(manifestProject).branch(branch).file(manifestFile)) {
      ManifestReader manifestReader = new ManifestReader(branch, manifestConfig.asString());
      return manifestReader.getProjects();
    } catch (ResourceNotFoundException e) {
      log.debug("Manifest for {} not found", branch);
      return new HashSet<>();
    }
  }

  private Set<String> applyConfig(String fromBranch, String toBranch, Set<String> inputProjects)
      throws ConfigInvalidException {
    Set<String> projects = new HashSet<>(inputProjects);
    List<String> setProjects =
        Arrays.asList(
            getConfig()
                .getStringList(
                    "automerger", fromBranch + BRANCH_DELIMITER + toBranch, "setProjects"));
    if (!setProjects.isEmpty()) {
      projects.clear();
      projects.addAll(setProjects);
      // if we set projects we can ignore the rest
      return projects;
    }
    List<String> addProjects =
        Arrays.asList(
            getConfig()
                .getStringList(
                    "automerger", fromBranch + BRANCH_DELIMITER + toBranch, "addProjects"));
    projects.addAll(addProjects);
    List<String> ignoreProjects =
        Arrays.asList(
            getConfig()
                .getStringList(
                    "automerger", fromBranch + BRANCH_DELIMITER + toBranch, "ignoreProjects"));
    projects.removeAll(ignoreProjects);
    return projects;
  }
}
