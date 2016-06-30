package com.googlesource.gerrit.plugins.automerger;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;

public class ConfigLoader {
  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
  private static final String configProject = "tools/automerger";
  private static final String configProjectBranch = "master";
  private static final String configFilename = "test.yaml";
  private Map<String, String> defaultManifestInfo;

  protected GerritApi gApi;
  Map<String, Map> config;
  private final List<String> GLOBAL_KEYS = Arrays.asList(
      "always_blank_merge",
      "blank_merge",
      "manifest",
      "ignore_source_manifest"
  );
  private final List<String> CONFIG_OPTION_KEYS = Arrays.asList(
      "manifest",
      "merge_all",
      "merge_manifest",
      "redirect_emails",
      "set_projects",
      "ignore_projects",
      "add_projects",
      "ignore_source_manifest"
  );

  public ConfigLoader(GerritApi gApi) {
    this.gApi = gApi;
    config = new HashMap<String, Map>();
  }

  public void loadConfig() throws IOException {
    try {
      BinaryResult configFile = gApi.projects().name(configProject).branch(configProjectBranch).file(configFilename);
      String configFileString = configFile.asString();
      Yaml yaml = new Yaml();
      config = (Map) yaml.load(configFileString);
      Map global = (Map) config.get("global");
      defaultManifestInfo = (Map<String, String>) global.get("manifest");
    } catch (RestApiException e) {
      log.error("REST API exception!", e);
    }
  }

  private Set<String> applyConfig(Set<String> projects, Map givenConfig) {
    Set filteredProjects = new HashSet<String>();
    filteredProjects.addAll(projects);

    if (givenConfig.containsKey("add_projects")) {
      Set addProjects = new HashSet((ArrayList) givenConfig.get("add_projects"));
      filteredProjects.addAll(addProjects);
    }
    if (givenConfig.containsKey("ignore_projects")) {
      Set ignoreProjects = new HashSet((ArrayList) givenConfig.get("ignore_projects"));
      filteredProjects.removeAll(ignoreProjects);
    }
    if (givenConfig.containsKey("set_projects")) {
      Set setProjects = new HashSet((ArrayList) givenConfig.get("set_projects"));
      filteredProjects = setProjects;
    }

    return filteredProjects;
  }

  // Returns overriden manifest map if specified, default if not
  private Map<String, String> getManifestInfo(String fromBranch) {
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(fromBranch);
    if (fromBranchConfig.containsKey("manifest"))
      return (Map<String, String>) fromBranchConfig.get("manifest");
    return defaultManifestInfo;
  }

  // TODO(stephenli): can probably make an overloaded grabKey method or something for things in general?
  // Returns overriden manifest map if specified, default if not
  private Map<String, String> getManifestInfo(String fromBranch, String toBranch) {
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(fromBranch);
    Map toBranchConfig = fromBranchConfig.get(toBranch);
    if (toBranchConfig.containsKey("manifest"))
      return (Map<String, String>) toBranchConfig.get("manifest");
    return defaultManifestInfo;
  }

  // Returns contents of manifest file for the given branch
  private Set<String> getManifestProjects(String fromBranch) throws RestApiException, IOException {
    Map<String, String> manifestProjectInfo = getManifestInfo(fromBranch);
    String manifestProject = manifestProjectInfo.get("project");
    String manifestFile = manifestProjectInfo.get("file");

    try {
      BinaryResult manifestConfig = gApi.projects().name(manifestProject).branch(fromBranch).file(manifestFile);
      ManifestReader manifestReader = new ManifestReader(fromBranch, manifestConfig.asString());
      return manifestReader.getProjects();
    } catch (ResourceNotFoundException e) {
      return new HashSet<String>();
    }
  }

  // Returns contents of manifest file for the given branch pair
  private Set<String> getManifestProjects(String fromBranch, String toBranch) throws RestApiException, IOException {
    Map<String, String> manifestProjectInfo = getManifestInfo(fromBranch, toBranch);
    String manifestProject = manifestProjectInfo.get("project");
    String manifestFile = manifestProjectInfo.get("file");

    try {
      BinaryResult manifestConfig = gApi.projects().name(manifestProject).branch(toBranch).file(manifestFile);
      ManifestReader manifestReader = new ManifestReader(toBranch, manifestConfig.asString());
      return manifestReader.getProjects();
    } catch (ResourceNotFoundException e) {
      return new HashSet<String>();
    }
  }

  public Set<String> getProjectsInScope(String fromBranch, String toBranch) throws RestApiException, IOException {
    try {
      Set<String> projectSet = new HashSet<String>();

      // TODO(stephenli): if not ignore_source_manifest
      Set<String> fromProjectSet = getManifestProjects(fromBranch);
      projectSet.addAll(fromProjectSet);

      Set<String> toProjectSet = getManifestProjects(fromBranch, toBranch);
      // Take intersection of project sets, unless one is empty. If empty, just use that one.
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

  public Set<String> getDownstreamBranches(String fromBranch, String project, boolean mergeAll)
      throws RestApiException, IOException {
    Set<String> downstreamBranches = new HashSet<String>();
    Map branches = (Map) config.get("branches");
    Map<String, Map> fromBranchConfig = (Map<String, Map>) branches.get(fromBranch);

    if (fromBranchConfig != null) {
      for (String key : fromBranchConfig.keySet()) {
        if (!CONFIG_OPTION_KEYS.contains(key)) {
          // If it's not a config option, then the key is the toBranch
          Map<String, Map> toBranchConfig = (Map<String, Map>) branches.get(key);
          // Add downstreams if merge_all is false, or if merge_all is true and it contains merge_all
          if (!mergeAll || (mergeAll && toBranchConfig.keySet().contains("merge_all"))) {
            Set<String> projectsInScope = getProjectsInScope(fromBranch, key);
            if (projectsInScope.contains(project)) {
              downstreamBranches.addAll(getDownstreamBranches(key, project, mergeAll));
              downstreamBranches.add(key);
            }
          }
        }
      }
    }
    return downstreamBranches;
  }
}
