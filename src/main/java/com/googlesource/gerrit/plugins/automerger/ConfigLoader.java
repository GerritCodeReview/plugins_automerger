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
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.re2j.Pattern;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

/** Class to read the config. */
@Singleton
public class ConfigLoader {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String BRANCH_DELIMITER = ":";
  private static final String DEFAULT_CONFLICT_MESSAGE = "Merge conflict found on ${branch}";

  private final GerritApi gApi;
  private final String pluginName;
  private final String canonicalWebUrl;
  private final AllProjectsName allProjectsName;
  private final PluginConfigFactory cfgFactory;
  private final Provider<CurrentUser> user;

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
      PluginConfigFactory cfgFactory,
      Provider<CurrentUser> user) {
    this.gApi = gApi;
    this.canonicalWebUrl = canonicalWebUrl;
    this.pluginName = pluginName;
    this.cfgFactory = cfgFactory;
    this.allProjectsName = allProjectsName;
    this.user = user;
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
    return automergeLabel != null ? automergeLabel : "Code-Review";
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
   * Returns a string to append to the end of the merge conflict message for the manifest project.
   *
   * @return The message string, or the empty string if nothing is specified.
   * @throws ConfigInvalidException
   */
  public String getManifestConflictMessage() throws ConfigInvalidException {
    String conflictMessage = getConfig().getString("global", null, "manifestConflictMessage");
    if (Strings.isNullOrEmpty(conflictMessage)) {
      conflictMessage = getConflictMessage();
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

      logger.atFine().log("Project set for %s to %s is %s", fromBranch, toBranch, projectSet);
      return projectSet;
    } catch (RestApiException | IOException e) {
      logger.atSevere().withCause(e).log("Error reading manifest for %s!", fromBranch);
      throw e;
    }
  }

  /**
   * Gets the upstream branches of the given branch and project.
   *
   * @param toBranch The downstream branch we would merge to.
   * @param project The project we are merging.
   * @return The branches upstream of the given branch for the given project.
   * @throws RestApiException
   * @throws IOException
   * @throws ConfigInvalidException
   */
  public Set<String> getUpstreamBranches(String toBranch, String project)
      throws ConfigInvalidException, RestApiException, IOException {
    if (toBranch == null) {
      throw new IllegalArgumentException("toBranch cannot be null");
    }
    Set<String> upstreamBranches = new HashSet<>();
    // List all subsections of automerger, split by :
    Set<String> subsections = getConfig().getSubsections(pluginName);
    for (String subsection : subsections) {
      // Subsections are of the form "fromBranch:toBranch"
      List<String> branchPair =
          Splitter.on(BRANCH_DELIMITER).trimResults().omitEmptyStrings().splitToList(subsection);
      if (branchPair.size() != 2) {
        throw new ConfigInvalidException("Automerger config branch pair malformed: " + subsection);
      }
      if (toBranch.equals(branchPair.get(1))) {
        // If toBranch matches, check if project is in both their manifests
        Set<String> projectsInScope = getProjectsInScope(branchPair.get(0), branchPair.get(1));
        if (projectsInScope.contains(project)) {
          upstreamBranches.add(branchPair.get(0));
        }
      }
    }
    return upstreamBranches;
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
    Set<String> downstreamBranches = new HashSet<>();
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

  public Set<String> getAllDownstreamBranches(String branch, String project)
      throws RestApiException, IOException, ConfigInvalidException {
    Set<String> downstreamBranches = new HashSet<>();
    Set<String> immediateDownstreams = getDownstreamBranches(branch, project);
    downstreamBranches.addAll(immediateDownstreams);
    for (String immediateDownstream : immediateDownstreams) {
      downstreamBranches.addAll(getAllDownstreamBranches(immediateDownstream, project));
    }
    return downstreamBranches;
  }

  public String getMissingDownstreamsMessage() throws ConfigInvalidException {
    String message = getConfig().getString("global", null, "missingDownstreamsMessage");
    if (message == null) {
      message =
          "Missing downstream branches ${missingDownstreams}. Please recreate the automerges. "
              + "If your topic contains quotes or braces, please remove them.";
    }
    return message;
  }

  public short getMinAutomergeVote() throws ConfigInvalidException {
    return (short) getConfig().getInt("global", "minAutomergeVote", -2);
  }

  public boolean minAutomergeVoteDisabled() throws ConfigInvalidException {
    return getConfig().getBoolean("global", "disableMinAutomergeVote", false);
  }

  public Account.Id getContextUserId(CurrentUser currentUser) throws ConfigInvalidException {
    int contextUserId = getConfig().getInt("global", "contextUserId", -1);
    if (contextUserId > 0) {
      return Account.id(contextUserId);
    }
    // Use the Guice injected user if one isn't provided.
    if(currentUser == null)
      return user.get().getAccountId();

    return currentUser.getAccountId();
  }

  public Account.Id getContextUserId() throws ConfigInvalidException {
    return getContextUserId(null);
  }

  /**
   * Returns overriden manifest config if specified, default if not
   *
   * @return The string name of the manifest project.
   * @throws ConfigInvalidException
   */
  public String getManifestProject() throws ConfigInvalidException {
    String manifestProject = getConfig().getString("global", null, "manifestProject");
    if (manifestProject == null) {
      throw new ConfigInvalidException("manifestProject not specified.");
    }
    return manifestProject;
  }

  // Returns overriden manifest config if specified, default if not
  private String getManifestFile() throws ConfigInvalidException {
    String manifestFile = getConfig().getString("global", null, "manifestFile");
    if (manifestFile == null) {
      throw new ConfigInvalidException("manifestFile not specified.");
    }
    return manifestFile;
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
      logger.atFine().log("Manifest for %s not found", branch);
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

  public ChangeMode changeMode() throws ConfigInvalidException {
    boolean cherryPickMode = getConfig().getBoolean("global", "cherryPickMode", false);

    return cherryPickMode ? ChangeMode.CHERRY_PICK : ChangeMode.MERGE;
  }
}
