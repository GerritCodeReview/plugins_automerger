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

import com.google.common.collect.Lists;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.project.BranchResource;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;

/** The logic behind auto-filling the branch map, aka the input to AutomergeChangeAction. */
class AllConfigDownstreamAction implements RestReadView<BranchResource> {

  protected ConfigLoader config;

  /**
   * Initializer for this class that sets the config.
   *
   * @param config Config for this plugin.
   */
  @Inject
  public AllConfigDownstreamAction(ConfigLoader config) {
    this.config = config;
  }

  /**
   * Return the list of all branch names that are downstream.
   *
   * @param branchResource BranchResource which we are attempting to find the downstreams of.
   * @return A list of all branch names downstream of the given branch.
   * @throws RestApiException
   * @throws IOException
   */
  @Override
  public Response<List<String>> apply(BranchResource branchResource)
      throws RestApiException, IOException {

    String branchName = branchResource.getBranchKey().getShortName();
    String projectName = branchResource.getName();

    try {
      Set<String> downstreamBranches = config.getAllDownstreamBranches(branchName, projectName);
      return Response.created(Lists.newArrayList(downstreamBranches));
    } catch (ConfigInvalidException e) {
      throw new ResourceConflictException(
          "Automerger configuration file is invalid: " + e.getMessage());
    }
  }
}
