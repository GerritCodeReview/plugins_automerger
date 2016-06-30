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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class LoadedConfig {
  private static final Logger log = LoggerFactory.getLogger(LoadedConfig.class);
  private final Map<String, Object> global;
  private final Map<String, Map> config;
  private final Map<String, String> defaultManifestInfo;
  private final Pattern blankMergePattern;
  private final Pattern alwaysBlankMergePattern;

  public LoadedConfig() {
    global = Collections.<String, Object>emptyMap();
    config = Collections.<String, Map>emptyMap();
    defaultManifestInfo = Collections.<String, String>emptyMap();
    blankMergePattern = Pattern.compile("");
    alwaysBlankMergePattern = Pattern.compile("");
  }

  public LoadedConfig(GerritApi gApi, String configProject,
                         String configProjectBranch, String configFilename,
                         List<String> configOptionKeys)
      throws IOException, RestApiException {
    log.info("Loading config file...");
    BinaryResult configFile = gApi.projects().name(configProject).branch(
        configProjectBranch).file(configFilename);
    String configFileString = configFile.asString();
    config = (Map<String, Map>) (new Yaml().load(configFileString));
    global = (Map<String, Object>) config.get("global");
    defaultManifestInfo = (Map<String, String>) global.get("manifest");

    List<String> blankMergeRegexes = (List<String>) global.get("blank_merge");
    Set<String> blankMergeStrings = new HashSet<String>();
    for (String blankMergeRegex : blankMergeRegexes) {
      blankMergeStrings.add(blankMergeRegex);
    }
    blankMergePattern = Pattern.compile(
        Joiner.on("|").join(blankMergeStrings), Pattern.DOTALL);

    List<String> alwaysBlankMergeRegexes =
        (List<String>) global.get("always_blank_merge");
    Set<String> alwaysBlankMergeStrings = new HashSet<String>();
    for (String alwaysBlankMergeRegex : alwaysBlankMergeRegexes) {
      alwaysBlankMergeStrings.add(alwaysBlankMergeRegex);
    }
    alwaysBlankMergePattern = Pattern.compile(
        Joiner.on("|").join(alwaysBlankMergeStrings), Pattern.DOTALL);
    log.info("Finished syncing automerger config.");
  }

  public boolean isSkipMerge(String fromBranch, String toBranch,
                                String commitMessage) {
    if (blankMergePattern == null && alwaysBlankMergePattern == null) {
      return false;
    }

    // If regex matches always_blank_merge (DO NOT MERGE ANYWHERE), skip.
    if (alwaysBlankMergePattern.matches(commitMessage)) {
      return true;
    }

    // If regex matches blank_merge (DO NOT MERGE), skip iff merge_all is false
    if (blankMergePattern.matches(commitMessage)) {
      Map<String, Object> mergePairConfig =
          getMergeConfig(fromBranch, toBranch);
      if (mergePairConfig != null) {
        boolean isMergeAll = (boolean) mergePairConfig.getOrDefault(
            "merge_all", false);
        return !isMergeAll;
      }
    }
    return false;
  }

  public Map<String, Map> getMergeConfig(String fromBranch) {
    return getBranches().get(fromBranch);
  }

  public Map<String, Object> getMergeConfig(String fromBranch,
                                               String toBranch) {
    Map<String, Map> fromBranchConfig = getBranches().get(fromBranch);
    if (fromBranchConfig == null) {
      return Collections.<String, Object>emptyMap();
    }
    return (Map<String, Object>) fromBranchConfig.get(toBranch);
  }

  public Map<String, Map> getBranches() {
    return (Map<String, Map>) config.get("branches");
  }

  public Map<String, Object> getGlobal() {
    return global;
  }

  public Map<String, String> getDefaultManifestInfo() {
    return defaultManifestInfo;
  }

  public String getAutomergeLabel() {
    return (String) global.getOrDefault("automerge_label", "Verified");
  }

  public String getCodeReviewLabel() {
    return (String) global.getOrDefault("code_review_label", "Code-Review");
  }

  public Object getGlobalAttribute(String key) {
    return global.get(key);
  }

  public Object getGlobalAttributeOrDefault(String key, Object def) {
    return global.getOrDefault(key, def);
  }
}