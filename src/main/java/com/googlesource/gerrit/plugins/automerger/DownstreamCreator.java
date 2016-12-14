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
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.RestoreInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.MergePatchSetInput;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.CommentAddedListener;
import com.google.gerrit.extensions.events.DraftPublishedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DownstreamCreator will receive an event on an uploaded, published, or restored patchset, and
 * upload a merge of the original patchset downstream, as determined by the configuration file. When
 * a topic or vote is changed on a patchset, or a change is abandoned, all downstream patchsets will
 * be modified as well.
 */
public class DownstreamCreator
    implements ChangeAbandonedListener,
        ChangeMergedListener,
        ChangeRestoredListener,
        CommentAddedListener,
        DraftPublishedListener,
        RevisionCreatedListener,
        TopicEditedListener {
  private static final Logger log = LoggerFactory.getLogger(DownstreamCreator.class);

  protected GerritApi gApi;
  protected ConfigLoader config;

  @Inject
  public DownstreamCreator(GerritApi gApi, ConfigLoader config) {
    this.gApi = gApi;
    this.config = config;
  }

  /**
   * Updates the config in memory if the config project is updated.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onChangeMerged(ChangeMergedListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      if (change.project.equals(config.configProject)
          && change.branch.equals(config.configProjectBranch)) {
        loadConfig();
      }
    } catch (RestApiException | IOException e) {
      log.error("Failed to reload config at {}", change.id, e);
    }
  }

  /**
   * Abandons downstream changes if a change is abandoned.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onChangeAbandoned(ChangeAbandonedListener.Event event) {
    ChangeInfo change = event.getChange();
    String revision = event.getRevision().commit.commit;
    log.debug("Detected revision {} abandoned on {}.", revision, change.project);
    abandonDownstream(change, revision);
  }

  /**
   * Updates downstream topics if a change has its topic modified.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onTopicEdited(TopicEditedListener.Event event) {
    ChangeInfo change = event.getChange();
    String oldTopic = event.getOldTopic();
    String revision = change.currentRevision;
    Set<String> downstreamBranches;
    try {
      downstreamBranches = config.getDownstreamBranches(change.branch, change.project);
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}", change.id, e);
      return;
    }

    if (downstreamBranches.isEmpty()) {
      log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
      return;
    }

    for (String downstreamBranch : downstreamBranches) {
      try {
        List<Integer> existingDownstream =
            getExistingMergesOnBranch(revision, oldTopic, downstreamBranch);
        for (Integer changeNumber : existingDownstream) {
          log.debug("Setting topic {} on {}", change.topic, changeNumber);
          gApi.changes().id(changeNumber).topic(change.topic);
        }
      } catch (RestApiException e) {
        log.error("RestApiException when editing downstream topics of {}", change.id, e);
      }
    }
  }

  /**
   * Updates downstream votes for a change each time a comment is made.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onCommentAdded(CommentAddedListener.Event event) {
    RevisionInfo eventRevision = event.getRevision();
    if (!eventRevision.isCurrent) {
      log.info(
          "Not updating downstream votes since revision {} is not current.", eventRevision._number);
      return;
    }
    ChangeInfo change = event.getChange();
    String revision = change.currentRevision;
    Set<String> downstreamBranches;
    try {
      downstreamBranches = config.getDownstreamBranches(change.branch, change.project);
    } catch (RestApiException | IOException e) {
      log.error("Failed to update downstream votes of {}", change.id, e);
      return;
    }

    if (downstreamBranches.isEmpty()) {
      log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
      return;
    }

    Map<String, ApprovalInfo> approvals = event.getApprovals();

    for (String downstreamBranch : downstreamBranches) {
      try {
        List<Integer> existingDownstream =
            getExistingMergesOnBranch(revision, change.topic, downstreamBranch);
        for (Integer changeNumber : existingDownstream) {
          ChangeInfo downstreamChange =
              gApi.changes().id(changeNumber).get(EnumSet.of(ListChangesOption.CURRENT_REVISION));
          for (Map.Entry<String, ApprovalInfo> label : approvals.entrySet()) {
            updateVote(downstreamChange, label.getKey(), label.getValue().value.shortValue());
          }
        }
      } catch (RestApiException e) {
        log.error("RestApiException when updating downstream votes of {}", change.id, e);
      }
    }
  }

  /**
   * Automerges changes downstream if a change is restored.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onChangeRestored(ChangeRestoredListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      automergeChanges(change, event.getRevision());
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}", change.id, e);
    }
  }

  /**
   * Automerges changes downstream if a draft is published.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onDraftPublished(DraftPublishedListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      automergeChanges(change, event.getRevision());
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}", change.id, e);
    }
  }

  /**
   * Automerges changes downstream if a revision is created.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      automergeChanges(change, event.getRevision());
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}", change.id, e);
    }
  }

  /**
   * Creates merges downstream, and votes -1 on the automerge label if we have a failed merge.
   *
   * @param mdsMergeInput Input containing the downstream branch map and source change ID.
   * @throws RestApiException Throws if we fail a REST API call.
   */
  public void createMergesAndHandleConflicts(MultipleDownstreamMergeInput mdsMergeInput)
      throws RestApiException {
    ReviewInput reviewInput = new ReviewInput();
    Map<String, Short> labels = new HashMap<String, Short>();
    short vote = 0;
    try {
      createDownstreamMerges(mdsMergeInput);

      reviewInput.message =
          "Automerging to "
              + Joiner.on(", ").join(mdsMergeInput.dsBranchMap.keySet())
              + " succeeded!";
      reviewInput.notify = NotifyHandling.NONE;
    } catch (FailedMergeException e) {
      reviewInput.message = e.displayConflicts();
      reviewInput.notify = NotifyHandling.ALL;
      vote = -1;
    }
    // Zero out automerge label if success, -1 vote if fail.
    labels.put(config.getAutomergeLabel(), vote);
    reviewInput.labels = labels;
    gApi.changes()
        .id(mdsMergeInput.sourceId)
        .revision(mdsMergeInput.currentRevision)
        .review(reviewInput);
  }

  /**
   * Creates merge downstream.
   *
   * @param mdsMergeInput Input containing the downstream branch map and source change ID.
   * @throws RestApiException Throws if we fail a REST API call.
   * @throws FailedMergeException Throws if we get a merge conflict when merging downstream.
   */
  public void createDownstreamMerges(MultipleDownstreamMergeInput mdsMergeInput)
      throws RestApiException, FailedMergeException {
    Map<String, String> failedMerges = new HashMap<String, String>();

    List<Integer> existingDownstream;
    for (String downstreamBranch : mdsMergeInput.dsBranchMap.keySet()) {
      // If there are existing downstream merges, update them
      // Otherwise, create them.
      try {
        boolean createDownstreams = true;
        if (mdsMergeInput.obsoleteRevision != null) {
          existingDownstream =
              getExistingMergesOnBranch(
                  mdsMergeInput.obsoleteRevision, mdsMergeInput.topic, downstreamBranch);
          if (!existingDownstream.isEmpty()) {
            log.debug(
                "Attempting to update downstream merge of {} on branch {}",
                mdsMergeInput.currentRevision,
                downstreamBranch);
            // existingDownstream should almost always be of length one, but
            // it's possible to construct it so that it's not
            for (Integer dsChangeNumber : existingDownstream) {
              updateDownstreamMerge(
                  mdsMergeInput.currentRevision,
                  mdsMergeInput.subject,
                  dsChangeNumber,
                  mdsMergeInput.dsBranchMap.get(downstreamBranch));
              createDownstreams = false;
            }
          }
        }
        if (createDownstreams) {
          log.debug(
              "Attempting to create downstream merge of {} on branch {}",
              mdsMergeInput.currentRevision,
              downstreamBranch);
          SingleDownstreamMergeInput sdsMergeInput = new SingleDownstreamMergeInput();
          sdsMergeInput.currentRevision = mdsMergeInput.currentRevision;
          sdsMergeInput.sourceId = mdsMergeInput.sourceId;
          sdsMergeInput.project = mdsMergeInput.project;
          sdsMergeInput.topic = mdsMergeInput.topic;
          sdsMergeInput.subject = mdsMergeInput.subject;
          sdsMergeInput.downstreamBranch = downstreamBranch;
          sdsMergeInput.doMerge = mdsMergeInput.dsBranchMap.get(downstreamBranch);
          createSingleDownstreamMerge(sdsMergeInput);
        }
      } catch (MergeConflictException e) {
        log.debug("Merge conflict from {} to {}", mdsMergeInput.currentRevision, downstreamBranch);
        failedMerges.put(downstreamBranch, e.getMessage());
        log.debug("Abandoning downstream of {}", mdsMergeInput.sourceId);
        abandonDownstream(
            gApi.changes().id(mdsMergeInput.sourceId).info(), mdsMergeInput.currentRevision);
      }
    }

    if (!failedMerges.keySet().isEmpty()) {
      throw new FailedMergeException(failedMerges, config.getExtraConflictMessage());
    }
  }

  /**
   * Get change IDs of the immediately downstream changes of the revision on the branch.
   *
   * @param upstreamRevision Revision of the original change.
   * @param topic Topic of the original change.
   * @param downstreamBranch Branch to check for existing merge CLs.
   * @return List of change numbers that are downstream of the given branch.
   * @throws RestApiException Throws when we fail a REST API call.
   */
  public List<Integer> getExistingMergesOnBranch(
      String upstreamRevision, String topic, String downstreamBranch) throws RestApiException {
    List<Integer> downstreamChangeNumbers = new ArrayList<Integer>();
    // get changes in same topic and check if their parent is upstreamRevision
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
          downstreamChangeNumbers.add(change._number);
        }
      }
    }
    return downstreamChangeNumbers;
  }

  /**
   * Create a single downstream merge.
   *
   * @param sdsMergeInput Input containing metadata for the merge.
   * @throws RestApiException
   */
  public void createSingleDownstreamMerge(SingleDownstreamMergeInput sdsMergeInput)
      throws RestApiException {

    String currentTopic = setTopic(sdsMergeInput.sourceId, sdsMergeInput.topic);

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = sdsMergeInput.currentRevision;

    log.debug("Creating downstream merge for {}", sdsMergeInput.currentRevision);
    ChangeInput downstreamChangeInput = new ChangeInput();
    downstreamChangeInput.project = sdsMergeInput.project;
    downstreamChangeInput.branch = sdsMergeInput.downstreamBranch;
    downstreamChangeInput.subject =
        sdsMergeInput.subject + " am: " + sdsMergeInput.currentRevision.substring(0, 10);
    downstreamChangeInput.topic = currentTopic;
    downstreamChangeInput.merge = mergeInput;

    if (!sdsMergeInput.doMerge) {
      mergeInput.strategy = "ours";
      downstreamChangeInput.subject =
          sdsMergeInput.subject + " skipped: " + sdsMergeInput.currentRevision.substring(0, 10);
      log.debug(
          "Skipping merge for {} to {}",
          sdsMergeInput.currentRevision,
          sdsMergeInput.downstreamBranch);
    }

    gApi.changes().create(downstreamChangeInput);
  }

  private void loadConfig() throws IOException, RestApiException {
    try {
      config.loadConfig();
    } catch (IOException | RestApiException e) {
      log.error("Config failed to sync!", e);
      throw e;
    }
  }

  private void automergeChanges(ChangeInfo change, RevisionInfo revisionInfo)
      throws RestApiException, IOException {
    if (revisionInfo.draft != null && revisionInfo.draft) {
      log.debug("Patchset {} is draft change, ignoring.", revisionInfo.commit.commit);
      return;
    }

    String currentRevision = revisionInfo.commit.commit;
    log.debug(
        "Handling patchsetevent with change id {} and revision {}", change.id, currentRevision);

    Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project);

    if (downstreamBranches.isEmpty()) {
      log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
      return;
    }

    // Map whether or not we should merge it or skip it for each downstream
    Map<String, Boolean> dsBranchMap = new HashMap<String, Boolean>();
    for (String downstreamBranch : downstreamBranches) {
      boolean isSkipMerge = config.isSkipMerge(change.branch, downstreamBranch, change.subject);
      dsBranchMap.put(downstreamBranch, !isSkipMerge);
    }
    log.debug("Automerging change {} from branch {}", change.id, change.branch);

    ChangeApi currentChange = gApi.changes().id(change._number);
    String previousRevision = getPreviousRevision(currentChange, revisionInfo._number);

    MultipleDownstreamMergeInput mdsMergeInput = new MultipleDownstreamMergeInput();
    mdsMergeInput.dsBranchMap = dsBranchMap;
    mdsMergeInput.sourceId = change.id;
    mdsMergeInput.project = change.project;
    mdsMergeInput.topic = change.topic;
    mdsMergeInput.subject = change.subject;
    mdsMergeInput.obsoleteRevision = previousRevision;
    mdsMergeInput.currentRevision = currentRevision;

    createMergesAndHandleConflicts(mdsMergeInput);
  }

  private void abandonDownstream(ChangeInfo change, String revision) {
    try {
      Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project);
      if (downstreamBranches.isEmpty()) {
        log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
        return;
      }

      for (String downstreamBranch : downstreamBranches) {
        List<Integer> existingDownstream =
            getExistingMergesOnBranch(revision, change.topic, downstreamBranch);
        log.debug("Abandoning existing downstreams: {}", existingDownstream);
        for (Integer changeNumber : existingDownstream) {
          abandonChange(changeNumber);
        }
      }
    } catch (RestApiException | IOException e) {
      log.error("Failed to abandon downstreams of {}", change.id, e);
    }
  }

  private void updateVote(ChangeInfo change, String label, short vote) throws RestApiException {
    if (label.equals(config.getAutomergeLabel())) {
      log.debug("Not updating automerge label, as it blocks when there is a merge conflict.");
      return;
    }
    log.debug("Giving {} for label {} to {}", vote, label, change.id);
    // Vote on all downstream branches unless merge conflict.
    ReviewInput reviewInput = new ReviewInput();
    Map<String, Short> labels = new HashMap<String, Short>();
    labels.put(label, vote);
    reviewInput.labels = labels;
    gApi.changes().id(change.id).revision(change.currentRevision).review(reviewInput);
  }

  private void updateDownstreamMerge(
      String newParentRevision, String upstreamSubject, Integer sourceNum, boolean doMerge)
      throws RestApiException {
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = newParentRevision;

    MergePatchSetInput mergePatchSetInput = new MergePatchSetInput();
    mergePatchSetInput.subject = upstreamSubject + " am: " + newParentRevision.substring(0, 10);
    if (!doMerge) {
      mergeInput.strategy = "ours";
      mergePatchSetInput.subject =
          upstreamSubject + " skipped: " + newParentRevision.substring(0, 10);
      log.debug("Skipping merge for {} on {}", newParentRevision, sourceNum);
    }
    mergePatchSetInput.merge = mergeInput;

    ChangeApi originalChange = gApi.changes().id(sourceNum);

    if (originalChange.info().status == ChangeStatus.ABANDONED) {
      RestoreInput restoreInput = new RestoreInput();
      restoreInput.message = "Restoring change due to upstream automerge.";
      originalChange.restore(restoreInput);
    }

    originalChange.createMergePatchSet(mergePatchSetInput);
  }

  private String getPreviousRevision(ChangeApi change, int currentPatchSetNumber)
      throws RestApiException {
    String previousRevision = null;
    int maxPatchSetNum = 0;
    if (currentPatchSetNumber > 1) {
      // Get sha of patch set with highest number we can see
      Map<String, RevisionInfo> revisionMap =
          change.get(EnumSet.of(ListChangesOption.ALL_REVISIONS)).revisions;
      for (Map.Entry<String, RevisionInfo> revisionEntry : revisionMap.entrySet()) {
        int revisionPatchNumber = revisionEntry.getValue()._number;
        if (revisionPatchNumber > maxPatchSetNum && revisionPatchNumber < currentPatchSetNumber) {
          previousRevision = revisionEntry.getKey();
          maxPatchSetNum = revisionPatchNumber;
        }
      }
    }
    return previousRevision;
  }

  private void abandonChange(Integer changeNumber) throws RestApiException {
    log.debug("Abandoning change: {}", changeNumber);
    AbandonInput abandonInput = new AbandonInput();
    abandonInput.notify = NotifyHandling.NONE;
    abandonInput.message = "Merge parent updated; abandoning due to upstream conflict.";
    gApi.changes().id(changeNumber).abandon(abandonInput);
  }

  private String setTopic(String sourceId, String topic) throws RestApiException {
    if (topic == null || topic.isEmpty()) {
      topic = "am-" + UUID.randomUUID().toString();
      log.debug("Setting original change {} topic to {}", sourceId, topic);
      gApi.changes().id(sourceId).topic(topic);
    }
    return topic;
  }
}
