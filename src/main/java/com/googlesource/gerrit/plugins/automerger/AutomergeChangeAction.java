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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.Map;
import org.eclipse.jgit.errors.ConfigInvalidException;

/** Implementation behind the "Recreate Automerges" button. */
class AutomergeChangeAction
    implements UiAction<RevisionResource>,
        RestModifyView<RevisionResource, AutomergeChangeAction.Input> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

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

  /**
   * Gets the input to the button and re-merges downstream based on the input.
   *
   * @param rev RevisionResource of the change whose page we are clicking the button.
   * @param input A map of branch to whether or not the merge should be "-s ours".
   * @return HTTP 200 on success.
   * @throws IOException
   * @throws RestApiException
   * @throws ConfigInvalidException
   * @throws StorageException
   */
  @Override
  public Response<?> apply(RevisionResource rev, Input input)
      throws IOException, RestApiException, StorageException, ConfigInvalidException {
    Map<String, Boolean> branchMap = input.branchMap;

    Change change = rev.getChange();
    if (branchMap == null) {
      logger.atFine().log("Branch map is empty for change %s", change.getKey().get());
      return Response.none();
    }
    String revision = rev.getPatchSet().commitId().name();

    MultipleDownstreamChangeInput mdsMergeInput = new MultipleDownstreamChangeInput();
    mdsMergeInput.dsBranchMap = branchMap;
    mdsMergeInput.changeNumber = change.getId().get();
    mdsMergeInput.patchsetNumber = rev.getPatchSet().number();
    mdsMergeInput.project = change.getProject().get();
    mdsMergeInput.topic = dsCreator.getOrSetTopic(change.getId().get(), change.getTopic(), config.getContextUserId());
    mdsMergeInput.subject = change.getSubject();
    mdsMergeInput.obsoleteRevision = revision;
    mdsMergeInput.currentRevision = revision;

    logger.atFine().log("Multiple downstream merge input: %s", mdsMergeInput.dsBranchMap);

    try {
      dsCreator.createChangesAndHandleConflicts(mdsMergeInput, config.getContextUserId());
    } catch (ConfigInvalidException e) {
      throw new ResourceConflictException(
          "Automerger configuration file is invalid: " + e.getMessage());
    } catch (InvalidQueryParameterException e) {
      throw new ResourceConflictException(
          "Topic or branch cannot have both braces and quotes: " + e.getMessage());
    }
    return Response.none();
  }

  /**
   * Description for what the DOM element for the button should look like.
   *
   * @param resource RevisionResource of the change whose page the button is on.
   * @return Description object that contains the right labels, visibility, etc.
   */
  @Override
  public Description getDescription(RevisionResource resource) {
    String project = resource.getProject().get();
    String branch = resource.getChange().getDest().shortName();
    Description desc = new Description();
    desc = desc.setLabel("Recreate automerges").setTitle("Recreate automerges downstream");
    try {
      if (config.getDownstreamBranches(branch, project).isEmpty()) {
        desc = desc.setVisible(false);
      } else {
        desc = desc.setVisible(user.get() instanceof IdentifiedUser);
      }
    } catch (RestApiException | IOException | ConfigInvalidException e) {
      logger.atSevere().log("Failed to recreate automerges for %s on %s", project, branch);
      desc = desc.setVisible(false);
    }
    return desc;
  }

  static class Input {
    Map<String, Boolean> branchMap;
  }
}
