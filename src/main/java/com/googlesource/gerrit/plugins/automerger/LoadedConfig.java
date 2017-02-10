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
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.re2j.Pattern;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** The loaded configuration stored in memory. */
public class LoadedConfig {
  private static final Logger log = LoggerFactory.getLogger(LoadedConfig.class);

  private final Map<String, Object> global;
  private final Map<String, Map> config;
  private final Map<String, String> defaultManifestInfo;
  private final Pattern blankMergePattern;
  private final Pattern alwaysBlankMergePattern;

  public LoadedConfig() {
    global = Collections.emptyMap();
    config = Collections.emptyMap();
    defaultManifestInfo = Collections.emptyMap();
    blankMergePattern = null;
    alwaysBlankMergePattern = null;
  }

  public LoadedConfig(
      GerritApi gApi,
      String configProject,
      String configProjectBranch,
      String configFilename,
      List<String> configOptionKeys)
      throws IOException, RestApiException {
    log.info(
        "Loading config file from project {} on branch {} and filename {}",
        configProject,
        configProjectBranch,
        configFilename);
    BinaryResult configFile =
        gApi.projects().name(configProject).branch(configProjectBranch).file(configFilename);
    String configFileString = configFile.asString();
    config = (Map<String, Map>) (new Yaml().load(configFileString));
    global = (Map<String, Object>) config.get("global");
    defaultManifestInfo = (Map<String, String>) global.get("manifest");

    blankMergePattern = getConfigPattern("blank_merge");
    alwaysBlankMergePattern = getConfigPattern("always_blank_merge");
    log.info("Finished syncing automerger config.");
  }

  /**
   * Checks to see if we should skip the change.
   *
   * <p>If the commit message matches the alwaysBlankMergePattern, always return true. If the commit
   * message matches the blankMergePattern and merge_all is false for this pair of branches, return
   * true.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @param commitMessage Commmit message of the original change.
   * @return Whether or not to merge with "-s ours".
   */
  public boolean isSkipMerge(String fromBranch, String toBranch, String commitMessage) {
    // If regex matches always_blank_merge (DO NOT MERGE ANYWHERE), skip.
    if (alwaysBlankMergePattern != null && alwaysBlankMergePattern.matches(commitMessage)) {
      return true;
    }

    // If regex matches blank_merge (DO NOT MERGE), skip iff merge_all is false
    if (blankMergePattern != null && blankMergePattern.matches(commitMessage)) {
      Map<String, Object> mergePairConfig = getMergeConfig(fromBranch, toBranch);
      if (mergePairConfig != null) {
        boolean isMergeAll = (boolean) mergePairConfig.getOrDefault("merge_all", false);
        return !isMergeAll;
      }
    }
    return false;
  }

  /**
   * Gets the merge configuration for this branch.
   *
   * @param fromBranch Branch we are merging from.
   * @return A map of config keys to their values, or a map of "to branches" to a map of config keys
   *     to their values.
   */
  public Map<String, Map> getMergeConfig(String fromBranch) {
    return getBranches().get(fromBranch);
  }

  /**
   * Gets the merge configuration for a pair of branches.
   *
   * @param fromBranch Branch we are merging from.
   * @param toBranch Branch we are merging to.
   * @return Map of configuration keys to their values.
   */
  public Map<String, Object> getMergeConfig(String fromBranch, String toBranch) {
    Map<String, Map> fromBranchConfig = getBranches().get(fromBranch);
    if (fromBranchConfig == null) {
      return Collections.emptyMap();
    }
    return (Map<String, Object>) fromBranchConfig.get(toBranch);
  }

  /**
   * Gets all the branches and their configuration information.
   *
   * @return A map of from branches to their configuration maps.
   */
  public Map<String, Map> getBranches() {
    return (Map<String, Map>) config.getOrDefault("branches", Collections.emptyMap());
  }

  /**
   * Gets the global config.
   *
   * @return A map of configuration keys to their values.
   */
  public Map<String, Object> getGlobal() {
    return global;
  }

  /**
   * Gets the default manifest information.
   *
   * @return A map of configuration keys to their default values.
   */
  public Map<String, String> getDefaultManifestInfo() {
    return defaultManifestInfo;
  }

  /**
   * Gets the automerge label (i.e. what to vote -1 on when we hit a merge conflict)
   *
   * @return The automerge label (by default, the String "Verified").
   */
  public String getAutomergeLabel() {
    return (String) global.getOrDefault("automerge_label", "Verified");
  }

  /**
   * Gets the value of a global attribute.
   *
   * @param key A configuration key that is defined in the config.
   * @return The value of the global attribute.
   */
  public Object getGlobalAttribute(String key) {
    return global.get(key);
  }

  /**
   * Gets the value of a global attribute, or the default value if it cannot be found.
   *
   * @param key A configuration key that is defined in the config.
   * @param def The default value if we cannot find it in the config.
   * @return The value of the global attribute, or the default value if it cannot be found.
   */
  public Object getGlobalAttributeOrDefault(String key, Object def) {
    return global.getOrDefault(key, def);
  }

  private Pattern getConfigPattern(String key) {
    Object patterns = global.get(key);
    if (patterns != null) {
      Set<String> mergeStrings = new HashSet<>((List<String>) patterns);
      return Pattern.compile(Joiner.on("|").join(mergeStrings), Pattern.DOTALL);
    }
    return null;
  }
}
