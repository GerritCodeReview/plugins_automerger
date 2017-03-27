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

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Branch.NameKey;
import com.google.gerrit.reviewdb.client.PatchSet.Id;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MergeValidator will validate that all downstream changes are uploaded for review before
 * submission.
 */
public class MergeValidator implements MergeValidationListener {
  private static final Logger log = LoggerFactory.getLogger(MergeValidator.class);

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
      CodeReviewCommit commit,
      ProjectState destProject,
      NameKey destBranch,
      Id patchSetId,
      IdentifiedUser caller)
      throws MergeValidationException {
    int changeId = commit.change().getChangeId();
    try {
      ChangeInfo upstreamChange =
          gApi.changes().id(changeId).get(EnumSet.of(ListChangesOption.CURRENT_REVISION));
      Set<String> missingDownstreams = getMissingDownstreamMerges(upstreamChange);
      if (!missingDownstreams.isEmpty()) {
        throw new MergeValidationException(
            "Missing downstream branches for "
                + missingDownstreams
                + ". Please recreate the automerges.");
      }
    } catch (RestApiException | IOException | ConfigInvalidException e) {
      log.error("Automerger plugin failed onPreMerge for {}", changeId, e);
      e.printStackTrace();
      throw new MergeValidationException("Error when validating merge for: " + changeId);
    }
  }

  public Set<String> getMissingDownstreamMerges(ChangeInfo upstreamChange)
      throws RestApiException, IOException, ConfigInvalidException {
    Set<String> missingDownstreamBranches = new HashSet<>();
    String topic = upstreamChange.topic;
    String upstreamRevision = upstreamChange.currentRevision;

    Set<String> downstreamBranches =
        config.getDownstreamBranches(upstreamChange.branch, upstreamChange.project);
    for (String downstreamBranch : downstreamBranches) {
      boolean dsExists = false;
      String query = "topic:" + topic + " status:open branch:" + downstreamBranch;
      List<ChangeInfo> changes =
          gApi.changes()
              .query(query)
              .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
              .get();
      for (ChangeInfo change : changes) {
        String changeRevision = change.currentRevision;
        RevisionInfo revision = change.revisions.get(changeRevision);
        List<CommitInfo> parents = revision.commit.parents;
        if (parents.size() > 1) {
          String secondParent = parents.get(1).commit;
          if (secondParent.equals(upstreamRevision)) {
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
