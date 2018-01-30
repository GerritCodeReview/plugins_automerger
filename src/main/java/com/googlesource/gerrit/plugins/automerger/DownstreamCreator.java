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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Joiner;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.RestoreInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.MergePatchSetInput;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.CommentAddedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.eclipse.jgit.errors.ConfigInvalidException;
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
        ChangeRestoredListener,
        CommentAddedListener,
        RevisionCreatedListener,
        TopicEditedListener {
  private static final Logger log = LoggerFactory.getLogger(DownstreamCreator.class);
  private static final String AUTOMERGER_TAG = "autogenerated:Automerger";
  private static final String MERGE_CONFLICT_TAG = "autogenerated:MergeConflict";
  private static final String SUBJECT_PREFIX = "automerger";
  private static final String SKIPPED_PREFIX = "skipped";
  private static final String CURRENT = "current";

  protected GerritApi gApi;
  protected ApiManager apiManager;
  protected ConfigLoader config;
  protected CurrentUser user;

  private final OneOffRequestContext oneOffRequestContext;

  @Inject
  public DownstreamCreator(
      GerritApi gApi,
      ApiManager apiManager,
      ConfigLoader config,
      OneOffRequestContext oneOffRequestContext) {
    this.gApi = gApi;
    this.apiManager = apiManager;
    this.config = config;
    this.oneOffRequestContext = oneOffRequestContext;
  }

  /**
   * Abandons downstream changes if a change is abandoned.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onChangeAbandoned(ChangeAbandonedListener.Event event) {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      ChangeInfo change = event.getChange();
      String revision = event.getRevision().commit.commit;
      log.debug("Detected revision {} abandoned on {}.", revision, change.project);
      abandonDownstream(change, revision);
    } catch (ConfigInvalidException | OrmException e) {
      log.error("Automerger plugin failed onChangeAbandoned for {}", event.getChange().id, e);
    }
  }

  /**
   * Updates downstream topics if a change has its topic modified.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onTopicEdited(TopicEditedListener.Event event) {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      ChangeInfo eventChange = event.getChange();
      // We have to re-query for this in order to include the current revision
      ChangeInfo change;
      try {
        change =
            gApi.changes()
                .id(eventChange._number)
                .get(EnumSet.of(ListChangesOption.CURRENT_REVISION));
      } catch (RestApiException e) {
        log.error("Automerger could not get change with current revision for onTopicEdited: ", e);
        return;
      }
      String oldTopic = event.getOldTopic();
      String revision = change.currentRevision;
      Set<String> downstreamBranches;
      try {
        downstreamBranches = config.getDownstreamBranches(change.branch, change.project);
      } catch (RestApiException | IOException | ConfigInvalidException e) {
        log.error("Failed to edit downstream topics of {}", change.id, e);
        return;
      }

      if (downstreamBranches.isEmpty()) {
        log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
        return;
      }

      // If change is empty, prevent someone breaking topic.
      if (isNullOrEmpty(change.topic)) {
        try {
          gApi.changes().id(change._number).topic(oldTopic);
          ReviewInput reviewInput = new ReviewInput();
          reviewInput.message(
              "Automerger prevented the topic from changing. Topic can only be modified on "
                  + "non-automerger-created CLs to a non-empty value.");
          reviewInput.notify = NotifyHandling.NONE;
          gApi.changes().id(change._number).revision(CURRENT).review(reviewInput);
        } catch (RestApiException e) {
          log.error("Failed to prevent setting empty topic for automerger plugin.", e);
        }
      } else {
        for (String downstreamBranch : downstreamBranches) {
          try {
            String toCrossHost = config.getToCrossHost(change.branch, downstreamBranch);
            List<Integer> existingDownstream =
                getExistingMergesOnBranch(revision, oldTopic, downstreamBranch, toCrossHost);
            for (Integer changeNumber : existingDownstream) {
              log.debug("Setting topic {} on {}", change.topic, changeNumber);
              apiManager.forHostname(toCrossHost).changes().id(changeNumber).topic(change.topic);
            }
          } catch (RestApiException | InvalidQueryParameterException e) {
            log.error("Failed to edit downstream topics of {}", change.id, e);
          }
        }
      }
    } catch (OrmException | ConfigInvalidException e) {
      log.error("Automerger plugin failed onTopicEdited for {}", event.getChange().id, e);
    }
  }

  /**
   * Updates downstream votes for a change each time a comment is made.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onCommentAdded(CommentAddedListener.Event event) {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      RevisionInfo eventRevision = event.getRevision();
      if (!eventRevision.isCurrent) {
        log.info(
            "Not updating downstream votes since revision {} is not current.",
            eventRevision._number);
        return;
      }
      ChangeInfo change = event.getChange();
      String revision = change.currentRevision;
      Set<String> downstreamBranches;
      downstreamBranches = config.getDownstreamBranches(change.branch, change.project);

      if (downstreamBranches.isEmpty()) {
        log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
        return;
      }

      Map<String, LabelInfo> labels =
          gApi.changes()
              .id(change._number)
              .get(EnumSet.of(ListChangesOption.DETAILED_LABELS))
              .labels;

      for (String downstreamBranch : downstreamBranches) {
        try {
          String toCrossHost = config.getToCrossHost(change.branch, downstreamBranch);
          List<Integer> existingDownstream =
              getExistingMergesOnBranch(revision, change.topic, downstreamBranch, toCrossHost);
          for (Integer changeNumber : existingDownstream) {
            ChangeInfo downstreamChange =
                apiManager
                    .forHostname(toCrossHost)
                    .changes()
                    .id(changeNumber)
                    .get(EnumSet.of(ListChangesOption.CURRENT_REVISION));
            for (Map.Entry<String, LabelInfo> labelEntry : labels.entrySet()) {
              if (labelEntry.getValue().all.size() > 0) {
                OptionalInt maxVote =
                    labelEntry
                        .getValue()
                        .all
                        .stream()
                        .filter(o -> o.value != null)
                        .mapToInt(i -> i.value)
                        .max();

                if (maxVote.isPresent()) {
                  updateVote(
                      downstreamChange,
                      labelEntry.getKey(),
                      (short) maxVote.getAsInt(),
                      toCrossHost);
                }
              }
            }
          }
        } catch (RestApiException | InvalidQueryParameterException e) {
          log.error("Exception when updating downstream votes of {}", change.id, e);
        }
      }
    } catch (OrmException | ConfigInvalidException | RestApiException | IOException e) {
      log.error("Automerger plugin failed onCommentAdded for {}", event.getChange().id, e);
    }
  }

  /**
   * Automerges changes downstream if a change is restored.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onChangeRestored(ChangeRestoredListener.Event event) {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      ChangeInfo change = event.getChange();
      automergeChanges(change, event.getRevision());
    } catch (RestApiException
        | IOException
        | ConfigInvalidException
        | InvalidQueryParameterException
        | OrmException e) {
      log.error("Automerger plugin failed onChangeRestored for {}", event.getChange().id, e);
    }
  }

  /**
   * Automerges changes downstream if a revision is created.
   *
   * @param event Event we are listening to.
   */
  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event event) {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      ChangeInfo change = event.getChange();
      automergeChanges(change, event.getRevision());
    } catch (RestApiException
        | IOException
        | ConfigInvalidException
        | InvalidQueryParameterException
        | OrmException e) {
      log.error("Automerger plugin failed onRevisionCreated for {}", event.getChange().id, e);
    }
  }

  /**
   * Creates merges downstream, and votes on the automerge label if we have a failed merge.
   *
   * @param mdsMergeInput Input containing the downstream branch map and source change ID.
   * @throws RestApiException Throws if we fail a REST API call.
   * @throws ConfigInvalidException Throws if we get a malformed configuration
   * @throws InvalidQueryParameterException Throws if we attempt to add an invalid value to query.
   * @throws OrmException Throws if we fail to open the request context
   */
  public void createMergesAndHandleConflicts(MultipleDownstreamMergeInput mdsMergeInput)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException,
          OrmException {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      ReviewInput reviewInput = new ReviewInput();
      Map<String, Short> labels = new HashMap<>();
      try {
        createDownstreamMerges(mdsMergeInput);

        reviewInput.message =
            "Automerging change "
                + mdsMergeInput.changeNumber
                + " to "
                + Joiner.on(", ").join(mdsMergeInput.dsBranchMap.keySet())
                + " succeeded!";
        reviewInput.notify = NotifyHandling.NONE;
      } catch (FailedMergeException e) {
        reviewInput.message = e.getDisplayString();
        reviewInput.notify = NotifyHandling.ALL;
        reviewInput.tag = MERGE_CONFLICT_TAG;
        // Vote minAutomergeVote if we hit a conflict.
        if (!config.minAutomergeVoteDisabled()) {
          labels.put(config.getAutomergeLabel(), config.getMinAutomergeVote());
        }
      }
      reviewInput.labels = labels;

      // Make the vote on the original change
      ChangeInfo originalChange =
          getOriginalChange(
              mdsMergeInput.changeNumber,
              mdsMergeInput.currentRevision,
              mdsMergeInput.sourceBranch,
              null);
      // if this fails, i.e. -2 is restricted, catch it and still post message without a vote.
      try {
        apiManager
            .forHostname(mdsMergeInput.fromCrossHostMap.get(originalChange.branch))
            .changes()
            .id(originalChange._number)
            .revision(CURRENT)
            .review(reviewInput);
      } catch (AuthException e) {
        reviewInput.labels = null;
        apiManager
            .forHostname(mdsMergeInput.fromCrossHostMap.get(originalChange.branch))
            .changes()
            .id(originalChange._number)
            .revision(CURRENT)
            .review(reviewInput);
      }
    }
  }

  /**
   * Creates merge downstream.
   *
   * @param mdsMergeInput Input containing the downstream branch map and source change ID.
   * @throws RestApiException Throws if we fail a REST API call.
   * @throws FailedMergeException Throws if we get a merge conflict when merging downstream.
   * @throws ConfigInvalidException Throws if we get a malformed config file
   * @throws InvalidQueryParameterException Throws if we attempt to add an invalid value to query.
   * @throws OrmException Throws if we fail to open the request context
   */
  public void createDownstreamMerges(MultipleDownstreamMergeInput mdsMergeInput)
      throws RestApiException, FailedMergeException, ConfigInvalidException,
          InvalidQueryParameterException, OrmException {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      // Map from branch to error message
      Map<String, String> failedMergeBranchMap = new TreeMap<>();

      List<Integer> existingDownstream;
      for (String downstreamBranch : mdsMergeInput.dsBranchMap.keySet()) {
        // If there are existing downstream merges, update them
        // Otherwise, create them.
        boolean createDownstreams = true;
        String toCrossHost = config.getToCrossHost(mdsMergeInput.sourceBranch, downstreamBranch);
        if (mdsMergeInput.obsoleteRevision != null) {
          existingDownstream =
              getExistingMergesOnBranch(
                  mdsMergeInput.obsoleteRevision,
                  mdsMergeInput.topic,
                  downstreamBranch,
                  toCrossHost);
          if (!existingDownstream.isEmpty()) {
            log.debug(
                "Attempting to update downstream merge of {} on branch {}",
                mdsMergeInput.currentRevision,
                downstreamBranch);
            // existingDownstream should almost always be of length one, but
            // it's possible to construct it so that it's not
            for (Integer dsChangeNumber : existingDownstream) {
              try {
                log.info("mds merge input: {}", mdsMergeInput);
                log.info("mds merge input crosshmap: {}", mdsMergeInput.toCrossHostMap);
                log.info(
                    "mds merge input crosshmap: {}",
                    mdsMergeInput.toCrossHostMap.get(downstreamBranch));
                updateDownstreamMerge(
                    mdsMergeInput.currentRevision,
                    mdsMergeInput.subject,
                    dsChangeNumber,
                    mdsMergeInput.dsBranchMap.get(downstreamBranch),
                    mdsMergeInput.changeNumber,
                    downstreamBranch,
                    mdsMergeInput.toCrossHostMap.get(downstreamBranch),
                    mdsMergeInput.fromCrossHostMap.get(mdsMergeInput.sourceBranch));
                createDownstreams = false;
              } catch (MergeConflictException e) {
                failedMergeBranchMap.put(downstreamBranch, e.getMessage());
                log.debug(
                    "Abandoning existing, obsolete {} due to merge conflict.", dsChangeNumber);
                abandonChange(dsChangeNumber, toCrossHost);
              }
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
          sdsMergeInput.changeNumber = mdsMergeInput.changeNumber;
          sdsMergeInput.project = mdsMergeInput.project;
          sdsMergeInput.topic = mdsMergeInput.topic;
          sdsMergeInput.subject = mdsMergeInput.subject;
          sdsMergeInput.downstreamBranch = downstreamBranch;
          sdsMergeInput.doMerge = mdsMergeInput.dsBranchMap.get(downstreamBranch);
          sdsMergeInput.fromCrossHost = mdsMergeInput.fromCrossHostMap.get(downstreamBranch);
          sdsMergeInput.toCrossHost = mdsMergeInput.toCrossHostMap.get(downstreamBranch);
          try {
            createSingleDownstreamMerge(sdsMergeInput);
          } catch (MergeConflictException e) {
            failedMergeBranchMap.put(downstreamBranch, e.getMessage());
          }
        }
      }

      if (!failedMergeBranchMap.isEmpty()) {
        throw new FailedMergeException(
            failedMergeBranchMap,
            mdsMergeInput.currentRevision,
            config.getHostName(),
            mdsMergeInput.project,
            mdsMergeInput.changeNumber,
            mdsMergeInput.patchsetNumber,
            config.getConflictMessage(),
            mdsMergeInput.topic);
      }
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
   * @throws InvalidQueryParameterException Throws when we try to add an invalid value to the query.
   * @throws ConfigInvalidException Throws if we fail to read the config
   * @throws OrmException Throws if we fail to open the request context
   */
  public List<Integer> getExistingMergesOnBranch(
      String upstreamRevision, String topic, String downstreamBranch, String hostname)
      throws RestApiException, InvalidQueryParameterException, OrmException,
          ConfigInvalidException {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      List<Integer> downstreamChangeNumbers = new ArrayList<>();
      List<ChangeInfo> changes = getChangesInTopicAndBranch(topic, downstreamBranch, hostname);

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
  }

  /**
   * Create a single downstream merge.
   *
   * @param sdsMergeInput Input containing metadata for the merge.
   * @throws RestApiException
   * @throws ConfigInvalidException
   * @throws InvalidQueryParameterException
   * @throws OrmException
   */
  public void createSingleDownstreamMerge(SingleDownstreamMergeInput sdsMergeInput)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException,
          OrmException {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      String currentTopic = getOrSetTopic(sdsMergeInput.changeNumber, sdsMergeInput.topic);

      if (isAlreadyMerged(sdsMergeInput, currentTopic)) {
        log.info(
            "Commit {} already merged into {}, not automerging again.",
            sdsMergeInput.currentRevision,
            sdsMergeInput.downstreamBranch);
        return;
      }

      MergeInput mergeInput = new MergeInput();
      mergeInput.source = sdsMergeInput.currentRevision;
      mergeInput.strategy = "recursive";

      log.debug("Creating downstream merge for {}", sdsMergeInput.currentRevision);
      ChangeInput downstreamChangeInput = new ChangeInput();
      downstreamChangeInput.project = sdsMergeInput.project;
      downstreamChangeInput.branch = sdsMergeInput.downstreamBranch;
      downstreamChangeInput.subject =
          getSubjectForDownstreamMerge(sdsMergeInput.subject, sdsMergeInput.currentRevision, false);
      downstreamChangeInput.topic = currentTopic;
      downstreamChangeInput.merge = mergeInput;
      downstreamChangeInput.notify = NotifyHandling.NONE;

      downstreamChangeInput.baseChange =
          getBaseChangeId(
              getChangeParents(sdsMergeInput.changeNumber, sdsMergeInput.currentRevision),
              sdsMergeInput.downstreamBranch,
              sdsMergeInput.fromCrossHost,
              sdsMergeInput.toCrossHost);

      if (!sdsMergeInput.doMerge) {
        mergeInput.strategy = "ours";
        downstreamChangeInput.subject =
            getSubjectForDownstreamMerge(
                sdsMergeInput.subject, sdsMergeInput.currentRevision, true);
        log.debug(
            "Skipping merge for {} to {}",
            sdsMergeInput.currentRevision,
            sdsMergeInput.downstreamBranch);
      }

      ChangeApi downstreamChange =
          apiManager.forHostname(sdsMergeInput.toCrossHost).changes().create(downstreamChangeInput);
      tagChange(downstreamChange.get(), "Automerger change created!", sdsMergeInput.toCrossHost);
    }
  }

  public String getOrSetTopic(int sourceId, String topic)
      throws RestApiException, OrmException, ConfigInvalidException {
    try (ManualRequestContext ctx = oneOffRequestContext.openAs(config.getContextUserId())) {
      if (isNullOrEmpty(topic)) {
        topic = "am-" + UUID.randomUUID();
        log.debug("Setting original change {} topic to {}", sourceId, topic);
        gApi.changes().id(sourceId).topic(topic);
      }
      return topic;
    }
  }

  /**
   * Get the base change ID that the downstream change should be based off of, given the parents.
   *
   * <p>Given changes A and B where A is the first parent of B, and where A' is the change whose
   * second parent is A, and B' is the change whose second parent is B, the first parent of B'
   * should be A'.
   *
   * @param parents Parent commit SHAs of the change
   * @return The base change ID that the change should be based off of, null if there is none.
   * @throws InvalidQueryParameterException
   * @throws RestApiException
   */
  private String getBaseChangeId(
      List<String> parents, String branch, String fromCrossHost, String toCrossHost)
      throws InvalidQueryParameterException, RestApiException {
    if (parents.isEmpty()) {
      log.info("No base change id for change with no parents.");
      return null;
    }
    // 1) Get topic of first parent
    String firstParentTopic = getTopic(parents.get(0), fromCrossHost);
    if (firstParentTopic == null) {
      return null;
    }
    // 2) query that topic and use that to find A'
    List<ChangeInfo> changesInTopic =
        getChangesInTopicAndBranch(firstParentTopic, branch, toCrossHost);
    String firstParent = parents.get(0);
    for (ChangeInfo change : changesInTopic) {
      List<CommitInfo> topicChangeParents =
          change.revisions.get(change.currentRevision).commit.parents;
      if (topicChangeParents.size() > 1 && topicChangeParents.get(1).commit.equals(firstParent)) {
        return String.valueOf(change._number);
      }
    }
    return null;
  }

  private void automergeChanges(ChangeInfo change, RevisionInfo revisionInfo)
      throws RestApiException, IOException, ConfigInvalidException, InvalidQueryParameterException,
          OrmException {
    String currentRevision = revisionInfo.commit.commit;
    log.debug(
        "Handling patchsetevent with change id {} and revision {}", change.id, currentRevision);

    Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project);

    if (downstreamBranches.isEmpty()) {
      log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
      return;
    }

    // Map of whether or not we should merge it or skip it for each downstream
    Map<String, Boolean> dsBranchMap = new HashMap<String, Boolean>();
    // Map of downstream branch to corresponding API to use.
    for (String downstreamBranch : downstreamBranches) {
      boolean isSkipMerge = config.isSkipMerge(change.branch, downstreamBranch, change.subject);
      dsBranchMap.put(downstreamBranch, !isSkipMerge);
    }
    log.debug("Automerging change {} from branch {}", change.id, change.branch);

    ChangeApi currentChange = gApi.changes().id(change._number);
    String previousRevision = getPreviousRevision(currentChange, revisionInfo._number);

    MultipleDownstreamMergeInput mdsMergeInput = new MultipleDownstreamMergeInput();
    mdsMergeInput.dsBranchMap = dsBranchMap;
    mdsMergeInput.changeNumber = change._number;
    mdsMergeInput.patchsetNumber = revisionInfo._number;
    mdsMergeInput.sourceBranch = change.branch;
    mdsMergeInput.project = change.project;
    mdsMergeInput.topic = getOrSetTopic(change._number, change.topic);
    mdsMergeInput.subject = change.subject;
    mdsMergeInput.obsoleteRevision = previousRevision;
    mdsMergeInput.currentRevision = currentRevision;
    mdsMergeInput.fromCrossHostMap = config.getFromCrossHostMap(change.branch, downstreamBranches);
    mdsMergeInput.toCrossHostMap = config.getToCrossHostMap(change.branch, downstreamBranches);

    createMergesAndHandleConflicts(mdsMergeInput);
  }

  private void abandonDownstream(ChangeInfo change, String revision)
      throws ConfigInvalidException, OrmException {
    try {
      Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project);
      if (downstreamBranches.isEmpty()) {
        log.debug("Downstream branches of {} on {} are empty", change.branch, change.project);
        return;
      }

      for (String downstreamBranch : downstreamBranches) {
        String toCrossHost = config.getToCrossHost(change.branch, downstreamBranch);

        List<Integer> existingDownstream =
            getExistingMergesOnBranch(revision, change.topic, downstreamBranch, toCrossHost);
        log.debug("Abandoning existing downstreams: {}", existingDownstream);
        for (Integer changeNumber : existingDownstream) {
          abandonChange(changeNumber, toCrossHost);
        }
      }
    } catch (RestApiException | IOException | InvalidQueryParameterException e) {
      log.error("Failed to abandon downstreams of {}", change.id, e);
    }
  }

  private void updateVote(ChangeInfo change, String label, short vote, String hostname)
      throws RestApiException {
    log.debug("Giving {} for label {} to {}", vote, label, change.id);
    // Vote on all downstream branches unless merge conflict.
    ReviewInput reviewInput = new ReviewInput();
    Map<String, Short> labels = new HashMap<String, Short>();
    labels.put(label, vote);
    reviewInput.labels = labels;
    reviewInput.notify = NotifyHandling.NONE;
    reviewInput.tag = AUTOMERGER_TAG;
    try {
      apiManager
          .forHostname(hostname)
          .changes()
          .id(change.id)
          .revision(CURRENT)
          .review(reviewInput);
    } catch (AuthException e) {
      log.error("Automerger could not set label, but still continuing.", e);
    }
  }

  private void tagChange(ChangeInfo change, String message, String hostname)
      throws RestApiException {
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.message(message);
    reviewInput.notify = NotifyHandling.NONE;
    reviewInput.tag = AUTOMERGER_TAG;
    try {
      apiManager
          .forHostname(hostname)
          .changes()
          .id(change.id)
          .revision(CURRENT)
          .review(reviewInput);
    } catch (AuthException e) {
      log.error("Automerger could not set label, but still continuing.", e);
    }
  }

  private void updateDownstreamMerge(
      String newParentRevision,
      String upstreamSubject,
      Integer existingDownstreamNum,
      boolean doMerge,
      Integer upstreamChangeNumber,
      String downstreamBranch,
      String fromCrossHost,
      String toCrossHost)
      throws RestApiException, InvalidQueryParameterException {
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = newParentRevision;

    MergePatchSetInput mergePatchSetInput = new MergePatchSetInput();

    mergePatchSetInput.subject =
        getSubjectForDownstreamMerge(upstreamSubject, newParentRevision, false);
    if (!doMerge) {
      mergeInput.strategy = "ours";
      mergePatchSetInput.subject =
          getSubjectForDownstreamMerge(upstreamSubject, newParentRevision, true);
      log.debug("Skipping merge for {} on {}", newParentRevision, existingDownstreamNum);
    }
    mergePatchSetInput.merge = mergeInput;

    mergePatchSetInput.baseChange =
        getBaseChangeId(
            getChangeParents(upstreamChangeNumber, newParentRevision),
            downstreamBranch,
            fromCrossHost,
            toCrossHost);

    ChangeApi originalChange =
        apiManager.forHostname(toCrossHost).changes().id(existingDownstreamNum);

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

  private ChangeInfo getOriginalChange(
      int changeNumber, String currentRevision, String downstreamBranch, String hostname)
      throws RestApiException, InvalidQueryParameterException, ConfigInvalidException {
    List<String> parents = getChangeParents(changeNumber, currentRevision);
    if (parents.size() >= 2) {
      String secondParentRevision = parents.get(1);
      String topic = apiManager.forHostname(hostname).changes().id(changeNumber).topic();
      List<ChangeInfo> changesInTopic = getChangesInTopic(topic, hostname);
      for (ChangeInfo change : changesInTopic) {
        String newHostname = hostname;
        String fromCrossHost = config.getFromCrossHost(change.branch, downstreamBranch);
        if (fromCrossHost != null) {
          newHostname = fromCrossHost;
        }
        if (change.currentRevision.equals(secondParentRevision)) {
          return getOriginalChange(
              change._number, secondParentRevision, change.branch, newHostname);
        }
      }
    }

    return apiManager.forHostname(hostname).changes().id(changeNumber).get();
  }

  private List<String> getChangeParents(int changeNumber, String currentRevision)
      throws RestApiException {
    ChangeApi change = gApi.changes().id(changeNumber);
    List<String> parents = new ArrayList<>();
    Map<String, RevisionInfo> revisionMap =
        change.get(EnumSet.of(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT))
            .revisions;
    List<CommitInfo> changeParents = revisionMap.get(currentRevision).commit.parents;
    for (CommitInfo commit : changeParents) {
      parents.add(commit.commit);
    }
    return parents;
  }

  private void abandonChange(Integer changeNumber, String toCrossHost) throws RestApiException {
    log.debug("Abandoning change: {}", changeNumber);
    AbandonInput abandonInput = new AbandonInput();
    abandonInput.notify = NotifyHandling.NONE;
    abandonInput.message = "Merge parent updated; abandoning due to upstream conflict.";
    apiManager.forHostname(toCrossHost).changes().id(changeNumber).abandon(abandonInput);
  }

  private String getTopic(String revision, String hostname)
      throws InvalidQueryParameterException, RestApiException {
    QueryBuilder queryBuilder = new QueryBuilder();
    queryBuilder.addParameter("commit", revision);
    List<ChangeInfo> changes =
        apiManager
            .forHostname(hostname)
            .changes()
            .query(queryBuilder.get())
            .withOption(ListChangesOption.CURRENT_REVISION)
            .get();
    if (!changes.isEmpty()) {
      for (ChangeInfo change : changes) {
        if (change.currentRevision.equals(revision) && !"".equals(change.topic)) {
          return change.topic;
        }
      }
    }
    return null;
  }

  private QueryBuilder constructTopicQuery(String topic) throws InvalidQueryParameterException {
    QueryBuilder queryBuilder = new QueryBuilder();
    queryBuilder.addParameter("topic", topic);
    queryBuilder.addParameter("status", "open");
    return queryBuilder;
  }

  private List<ChangeInfo> getChangesInTopic(String topic, String hostname)
      throws InvalidQueryParameterException, RestApiException {
    QueryBuilder queryBuilder = constructTopicQuery(topic);
    return apiManager
        .forHostname(hostname)
        .changes()
        .query(queryBuilder.get())
        .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
        .get();
  }

  private List<ChangeInfo> getChangesInTopicAndBranch(
      String topic, String downstreamBranch, String hostname)
      throws InvalidQueryParameterException, RestApiException {
    QueryBuilder queryBuilder = constructTopicQuery(topic);
    queryBuilder.addParameter("branch", downstreamBranch);
    return apiManager
        .forHostname(hostname)
        .changes()
        .query(queryBuilder.get())
        .withOptions(ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT)
        .get();
  }

  private boolean isAlreadyMerged(SingleDownstreamMergeInput sdsMergeInput, String currentTopic)
      throws InvalidQueryParameterException, RestApiException {
    // If we've already merged this commit to this branch, don't do it again.
    List<ChangeInfo> changes =
        getChangesInTopicAndBranch(
            currentTopic, sdsMergeInput.downstreamBranch, sdsMergeInput.toCrossHost);
    for (ChangeInfo change : changes) {
      if (change.branch.equals(sdsMergeInput.downstreamBranch)) {
        List<CommitInfo> parents = change.revisions.get(change.currentRevision).commit.parents;
        if (parents.size() > 1) {
          String secondParent = parents.get(1).commit;
          if (secondParent.equals(sdsMergeInput.currentRevision)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Create subject line for downstream merge with metadata from upstream change.
   *
   * <p>The downstream subject will be in the format: "[automerger] upstreamSubject am:
   * upstreamRevision". If it is a skip, "am" will be replaced with "skipped", and [automerger]
   * replaced with [automerger skipped].
   *
   * @param upstreamSubject Subject line of the upstream change
   * @param upstreamRevision Commit SHA1 of the upstream change
   * @param skipped Whether or not the merge is done with "-s ours"
   * @return Subject line for downstream merge
   */
  private String getSubjectForDownstreamMerge(
      String upstreamSubject, String upstreamRevision, boolean skipped) {
    if (!upstreamSubject.startsWith("[" + SUBJECT_PREFIX)) {
      String prefix = "[" + SUBJECT_PREFIX + "]";
      if (skipped) {
        prefix = "[" + SUBJECT_PREFIX + " " + SKIPPED_PREFIX + "]";
      }
      upstreamSubject = Joiner.on(" ").join(prefix, upstreamSubject);
    }
    String denotationString = skipped ? "skipped:" : "am:";
    return Joiner.on(" ")
        .join(upstreamSubject, denotationString, upstreamRevision.substring(0, 10));
  }
}
