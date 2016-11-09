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

import com.google.gerrit.extensions.restapi.DefaultInput;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.RevisionResource;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class ConfigDownstreamAction
    implements RestModifyView<RevisionResource, ConfigDownstreamAction.Input> {

  protected ConfigLoader config;

  @Inject
  public ConfigDownstreamAction(ConfigLoader config) {
    this.config = config;
  }

  @Override
  public Response<Map<String, Boolean>> apply(RevisionResource rev, Input input)
      throws RestApiException, IOException {

    String branchName = rev.getChange().getDest().getShortName();
    String projectName = rev.getProject().get();

    Set<String> downstreamBranches = config.getDownstreamBranches(branchName, projectName);
    Map<String, Boolean> downstreamMap = new HashMap<>();
    for (String downstreamBranch : downstreamBranches) {
      boolean isSkipMerge = config.isSkipMerge(branchName, downstreamBranch, input.subject);
      downstreamMap.put(downstreamBranch, !isSkipMerge);
    }
    return Response.created(downstreamMap);
  }

  static class Input {
    @DefaultInput String subject;
  }
}
