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

import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.DefaultInput;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;

/** The logic behind auto-filling the branch map, aka the input to AutomergeChangeAction. */
class ConfigDownstreamAction
    implements RestModifyView<ChangeResource, ConfigDownstreamAction.Input> {

  protected ConfigLoader config;

  /**
   * Initializer for this class that sets the config.
   *
   * @param config Config for this plugin.
   */
  @Inject
  public ConfigDownstreamAction(ConfigLoader config) {
    this.config = config;
  }

  /**
   * Return the map of branch names to whether or not we should merge with "-s ours".
   *
   * @param change ChangeResource of the change whose page we are on.
   * @param input The subject of the change (since it can modify our map, i.e. DO NOT MERGE)
   * @return The map of branch names to whether or not to skip them (i.e. merge with "-s ours")
   * @throws RestApiException
   * @throws IOException
   */
  @Override
  public Response<Map<String, Boolean>> apply(ChangeResource change, Input input)
      throws RestApiException, IOException {

    String branchName = change.getChange().getDest().getShortName();
    String projectName = change.getProject().get();

    try {
      Set<String> downstreamBranches = config.getDownstreamBranches(branchName, projectName);
      Map<String, Boolean> downstreamMap = new HashMap<>();
      for (String downstreamBranch : downstreamBranches) {
        boolean isSkipMerge = config.isSkipMerge(branchName, downstreamBranch, input.subject);
        downstreamMap.put(downstreamBranch, !isSkipMerge);
      }
      return Response.created(downstreamMap);
    } catch (ConfigInvalidException e) {
      throw new ResourceConflictException(
          "Automerger configuration file is invalid: " + e.getMessage());
    }
  }

  static class Input {
    @DefaultInput String subject;
  }
}
