// Copyright (C) 2017 The Android Open Source Project
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;

/**
 * MergeValidator will validate that all downstream changes are uploaded for review before
 * submission.
 */
public class MergeValidator implements MergeValidationListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected GerritApi gApi;
  protected ConfigLoader config;

  @Inject
  public MergeValidator(GerritApi gApi, ConfigLoader config) {
    this.gApi = gApi;
    this.config = config;
  }

  @Override
  public void onPreMerge(
      Repository repo,
      CodeReviewRevWalk revWalk,
      CodeReviewCommit commit,
      ProjectState destProject,
      BranchNameKey destBranch,
      PatchSet.Id patchSetId,
      IdentifiedUser caller)
      throws MergeValidationException {
    int changeId = commit.change().getChangeId();
    try {
      ChangeInfo upstreamChange =
          gApi.changes().id(changeId).get(EnumSet.of(ListChangesOption.CURRENT_REVISION));
      Set<String> missingDownstreams = getMissingDownstreamMerges(upstreamChange);
      if (!missingDownstreams.isEmpty()) {
        throw new MergeValidationException(getMissingDownstreamsMessage(missingDownstreams));
      }
    } catch (RestApiException
        | IOException
        | ConfigInvalidException
        | InvalidQueryParameterException e) {
      logger.atSevere().withCause(e).log("Automerger plugin failed onPreMerge for %s", changeId);
      e.printStackTrace();
      throw new MergeValidationException("Error when validating merge for: " + changeId);
    }
  }

  private String getMissingDownstreamsMessage(Set<String> missingDownstreams)
      throws ConfigInvalidException {
    String missingDownstreamsMessage = config.getMissingDownstreamsMessage();
    ParameterizedString pattern = new ParameterizedString(missingDownstreamsMessage);
    return pattern.replace(getSubstitutionMap(missingDownstreams));
  }

  private Map<String, String> getSubstitutionMap(Set<String> missingDownstreams) {
    Map<String, String> substitutionMap = new HashMap<>();
    substitutionMap.put("missingDownstreams", Joiner.on(", ").join(missingDownstreams));
    return substitutionMap;
  }

  @VisibleForTesting
  protected Set<String> getMissingDownstreamMerges(ChangeInfo upstreamChange)
      throws RestApiException, IOException, ConfigInvalidException, InvalidQueryParameterException {
    Set<String> missingDownstreamBranches = new HashSet<>();

    Set<String> downstreamBranches =
        config.getDownstreamBranches(upstreamChange.branch, upstreamChange.project);
    for (String downstreamBranch : downstreamBranches) {
      boolean dsExists = false;
      QueryBuilder queryBuilder = new QueryBuilder();
      if (upstreamChange.topic == null || upstreamChange.topic.equals("")) {
        // If topic is null or empty, we immediately know that downstream is missing.
        missingDownstreamBranches.add(downstreamBranch);
        continue;
      }
      queryBuilder.addParameter("topic", upstreamChange.topic);
      queryBuilder.addParameter("branch", downstreamBranch);
      queryBuilder.addParameter("status", "open");
      List<ChangeInfo> changes =
          gApi.changes()
              .query(queryBuilder.get())
              .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
              .get();
      for (ChangeInfo change : changes) {
        RevisionInfo revision = change.revisions.get(change.currentRevision);
        List<CommitInfo> parents = revision.commit.parents;
        if (parents.size() > 1) {
          String secondParent = parents.get(1).commit;
          if (secondParent.equals(upstreamChange.currentRevision)) {
            dsExists = true;
            break;
          }
        }
      }
      if (!dsExists) {
        missingDownstreamBranches.add(downstreamBranch);
      }
    }
    return missingDownstreamBranches;
  }
}
