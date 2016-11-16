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

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.events.EventFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AutomergeChangeAction
    implements UiAction<RevisionResource>,
        RestModifyView<RevisionResource, AutomergeChangeAction.Input> {
  private static final Logger log = LoggerFactory.getLogger(AutomergeChangeAction.class);

  private Provider<CurrentUser> user;
  private ConfigLoader config;
  private DownstreamCreator dsCreator;

  @Inject
  AutomergeChangeAction(
      Provider<CurrentUser> user, ConfigLoader config, DownstreamCreator dsCreator) {
    this.user = user;
    this.config = config;
    this.dsCreator = dsCreator;
  }

  @Override
  public Object apply(RevisionResource rev, Input input)
      throws RestApiException, FailedMergeException {
    Map<String, Boolean> branchMap = input.branchMap;
    Change change = rev.getChange();
    if (branchMap == null) {
      log.info("Branch map is empty for change {}", change.getKey().get());
      return Response.none();
    }
    String revision = rev.getPatchSet().getRevision().get();

    MultipleDownstreamMergeInput mdsMergeInput = new MultipleDownstreamMergeInput();
    mdsMergeInput.dsBranchMap = branchMap;
    mdsMergeInput.sourceId = change.getKey().get();
    mdsMergeInput.project = change.getProject().get();
    mdsMergeInput.topic = change.getTopic();
    mdsMergeInput.subject = change.getSubject();
    mdsMergeInput.obsoleteRevision = revision;
    mdsMergeInput.currentRevision = revision;

    log.info("Multiple downstream merge input: {}", mdsMergeInput.dsBranchMap);

    dsCreator.createMergesAndHandleConflicts(mdsMergeInput);
    return Response.none();
  }

  @Override
  public Description getDescription(RevisionResource resource) {
    String project = resource.getProject().get();
    String branch = resource.getChange().getDest().getShortName();
    Description desc = new Description();
    desc = desc.setLabel("Recreate automerges").setTitle("Recreate automerges downstream");
    try {
      if (config.getDownstreamBranches(branch, project).isEmpty()) {
        desc = desc.setVisible(false);
      } else {
        desc = desc.setVisible(user.get() instanceof IdentifiedUser);
      }
    } catch (RestApiException | IOException e) {
      log.error("Failed to recreate automerges for {} on {}", project, branch);
      desc = desc.setVisible(false);
    }
    return desc;
  }

  static class Input {
    Map<String, Boolean> branchMap;
  }
}
