package com.googlesource.gerrit.plugins.automerger;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

public class ConfigLoader {
  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
  String configProject;
  String configProjectBranch;
  String configFilename;
  List<String> globalKeys;
  Yaml yaml;
  Map global;
  private Map<String, String> defaultManifestInfo;
  public List<String> configOptionKeys;
  Set<Pattern> blankMergePatterns;

  protected GerritApi gApi;
  Map<String, Map> config;

  public ConfigLoader(GerritApi gApi) throws IOException {
    yaml = new Yaml();
    this.gApi = gApi;
    config = new HashMap<String, Map>();

    InputStream inputStream = ConfigLoader.class.getResourceAsStream(
        "/main/config/config_keys.yaml");
    String automergerConfigYamlString = CharStreams.toString(
        new InputStreamReader(inputStream, Charsets.UTF_8));
    inputStream.close();
    Map automergerConfig = (Map) yaml.load(automergerConfigYamlString);
    configProject = (String) automergerConfig.get("config_project");
    configProjectBranch = (String) automergerConfig.get("config_project_branch");
    configFilename = (String) automergerConfig.get("config_filename");
    globalKeys = (List<String>) automergerConfig.get("global_keys");
    configOptionKeys = (List<String>) automergerConfig.get("config_option_keys");
  }

  public void loadConfig() throws IOException {
    try {
      BinaryResult configFile = gApi.projects().name(configProject).branch(
          configProjectBranch).file(configFilename);
      String configFileString = configFile.asString();
      config = (Map) yaml.load(configFileString);
      global = (Map) config.get("global");
      defaultManifestInfo = (Map<String, String>) global.get("manifest");
      blankMergePatterns = new HashSet<Pattern>();
      List<String> blankMergeRegexes = (List<String>) global.get("blank_merge");
      for (String blankMergeRegex : blankMergeRegexes) {
        blankMergePatterns.add(Pattern.compile(blankMergeRegex));
      }
    } catch (RestApiException e) {
      log.error("REST API exception!", e);
    }
  }

  // Returns true if matches DO NOT MERGE regex and merge_all is false
  public boolean isSkipMerge(String fromBranch, String toBranch,
                             String commitMessage) {
    if (blankMergePatterns.isEmpty()) {
      return false;
    }

    for (Pattern blankMergePattern : blankMergePatterns) {
      Matcher regexMatcher = blankMergePattern.matcher(commitMessage);
      if (regexMatcher.find()) {
        // If it is DO NOT MERGE, return true if merge_all is false
        Map<String, Object> mergePairConfig = getConfig(fromBranch, toBranch);
        if (mergePairConfig != null) {
          boolean isMergeAll = (boolean) mergePairConfig.getOrDefault(
              "merge_all", false);
          return !isMergeAll;
        }
      }
    }
    return false;
  }

  public Map<String, Object> getConfig(String fromBranch, String toBranch) {
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(
        fromBranch);
    if (fromBranchConfig == null) {
      return null;
    }
    return (Map<String, Object>) fromBranchConfig.get(toBranch);
  }

  private Set<String> applyConfig(Set<String> projects, Map givenConfig) {
    Set filteredProjects = new HashSet<String>();
    filteredProjects.addAll(projects);

    if (givenConfig.containsKey("add_projects")) {
      Set addProjects = new HashSet((ArrayList) givenConfig.get(
          "add_projects"));
      filteredProjects.addAll(addProjects);
    }
    if (givenConfig.containsKey("ignore_projects")) {
      Set ignoreProjects = new HashSet((ArrayList) givenConfig.get(
          "ignore_projects"));
      filteredProjects.removeAll(ignoreProjects);
    }
    if (givenConfig.containsKey("set_projects")) {
      Set setProjects = new HashSet((ArrayList) givenConfig.get(
          "set_projects"));
      filteredProjects = setProjects;
    }

    return filteredProjects;
  }

  // Returns overriden manifest map if specified, default if not
  private Map<String, String> getManifestInfo(String fromBranch) {
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(
        fromBranch);
    if (fromBranchConfig.containsKey("manifest"))
      return (Map<String, String>) fromBranchConfig.get("manifest");
    return defaultManifestInfo;
  }

  // Returns overriden manifest map if specified, default if not
  private Map<String, String> getManifestInfo(String fromBranch,
                                              String toBranch) {
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(
        fromBranch);
    Map toBranchConfig = fromBranchConfig.get(toBranch);
    if (toBranchConfig.containsKey("manifest"))
      return (Map<String, String>) toBranchConfig.get("manifest");
    return defaultManifestInfo;
  }

  // Returns contents of manifest file for the given branch
  private Set<String> getManifestProjects(String fromBranch)
      throws RestApiException, IOException {
    Map<String, String> manifestProjectInfo = getManifestInfo(fromBranch);
    String manifestProject = manifestProjectInfo.get("project");
    String manifestFile = manifestProjectInfo.get("file");

    try {
      BinaryResult manifestConfig = gApi.projects().name(
          manifestProject).branch(fromBranch).file(manifestFile);
      ManifestReader manifestReader = new ManifestReader(
          fromBranch, manifestConfig.asString());
      return manifestReader.getProjects();
    } catch (ResourceNotFoundException e) {
      return new HashSet<String>();
    }
  }

  // Returns contents of manifest file for the given branch pair
  private Set<String> getManifestProjects(String fromBranch, String toBranch)
      throws RestApiException, IOException {
    Map<String, String> manifestProjectInfo = getManifestInfo(fromBranch,
        toBranch);
    String manifestProject = manifestProjectInfo.get("project");
    String manifestFile = manifestProjectInfo.get("file");

    try {
      BinaryResult manifestConfig = gApi.projects().name(
          manifestProject).branch(toBranch).file(manifestFile);
      ManifestReader manifestReader = new ManifestReader(
          toBranch, manifestConfig.asString());
      return manifestReader.getProjects();
    } catch (ResourceNotFoundException e) {
      return new HashSet<String>();
    }
  }

  public String getAutomergeLabel() {
    return (String) global.getOrDefault("automerge_label", "Verified");
  }

  public Set<String> getProjectsInScope(String fromBranch, String toBranch)
      throws RestApiException, IOException {
    try {
      Set<String> projectSet = new HashSet<String>();

      // TODO(stephenli): if not ignore_source_manifest
      Set<String> fromProjectSet = getManifestProjects(fromBranch);
      projectSet.addAll(fromProjectSet);

      Set<String> toProjectSet = getManifestProjects(fromBranch, toBranch);
      // Take intersection of project sets, unless one is empty.
      // If empty, just use that one.
      if (projectSet.isEmpty())
        projectSet = toProjectSet;
      else if (!toProjectSet.isEmpty())
        projectSet.retainAll(toProjectSet);

      projectSet = applyConfig(projectSet, (Map) config.get("global"));
      Map branches = (Map) config.get("branches");
      Map fromBranchConfig = (Map) branches.get(fromBranch);
      projectSet = applyConfig(projectSet, fromBranchConfig);
      Map toBranchConfig = (Map) fromBranchConfig.get(toBranch);
      projectSet = applyConfig(projectSet, toBranchConfig);

      return projectSet;
    } catch (RestApiException|IOException e) {
      log.error("Error reading manifest for " + fromBranch + "!", e);
      throw e;
    }
  }

  // If change has DO NOT MERGE, includeMergeAll should be true
  public Set<String> getDownstreamBranches(String fromBranch, String project)
      throws RestApiException, IOException {
    Set<String> downstreamBranches = new HashSet<String>();
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(
        fromBranch);

    if (fromBranchConfig != null) {
      for (String key : fromBranchConfig.keySet()) {
        if (!configOptionKeys.contains(key)) {
          // If it's not a config option, then the key is the toBranch
          Map<String, Object> toBranchConfig =
              (Map<String, Object>) fromBranchConfig.get(key);
          Set<String> projectsInScope = getProjectsInScope(fromBranch, key);
          if (projectsInScope.contains(project)) {
            downstreamBranches.add(key);
          }
        }
      }
    }
    return downstreamBranches;
  }
}
