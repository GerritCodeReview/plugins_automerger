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
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.DraftPublishedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.gerrit.server.events.TopicChangedEvent;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class DownstreamCreator
    implements ChangeAbandonedListener,
                   ChangeMergedListener,
                   ChangeRestoredListener,
                   DraftPublishedListener,
                   RevisionCreatedListener,
                   TopicEditedListener {
  private static final Logger log = LoggerFactory.getLogger(
      DownstreamCreator.class);

  protected GerritApi gApi;
  protected ConfigLoader config;

  @Inject
  public DownstreamCreator(GerritApi gApi, ConfigLoader config) {
    this.gApi = gApi;
    this.config = config;
  }

  private void loadConfig() throws IOException, RestApiException{
    try {
      config.loadConfig();
    } catch (Exception e) {
      log.error("Config failed to sync!", e);
      throw e;
    }
  }

  @Override
  public void onChangeMerged(ChangeMergedListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      if (change.project.equals(config.configProject) &&
              change.branch.equals(config.configProjectBranch)) {
        loadConfig();
      }
    } catch (RestApiException | IOException e) {
      log.error("Failed to reload config at {}: {}", change.id, e);
    }
  }

  @Override
  public void onChangeAbandoned(ChangeAbandonedListener.Event event) {
    ChangeInfo change = event.getChange();
    String revision = event.getRevision().commit.commit;
    try {
      List<Integer> existingDownstream = getExistingDownstreamMerges(revision,
          change.topic, change.branch);
      for (Integer changeNumber : existingDownstream) {
        log.info("Abandoning downstream of {}: {}", revision, changeNumber);
        abandonChange(changeNumber);
      }
    } catch (RestApiException e) {
      log.error("Failed to abandon downstreams of {}: {}", change.id, e);
    }
  }

  @Override
  public void onTopicEdited(TopicEditedListener.Event event) {
    ChangeInfo change = event.getChange();
    String oldTopic = event.getOldTopic();
    try {
      String revision = gApi.changes().id(change.id).get(
          EnumSet.of(ListChangesOption.CURRENT_REVISION)).currentRevision;
      List<Integer> existingDownstream = getExistingDownstreamMerges(revision,
          oldTopic, change.branch);
      for (Integer changeNumber : existingDownstream) {
        log.info("Setting topic {} on {}", change.topic, changeNumber);
        gApi.changes().id(changeNumber).topic(change.topic);
      }
    } catch (RestApiException e) {
      log.error("Failed to edit downstream topics of {}: {}", change.id, e);
    }
  }

  @Override
  public void onChangeRestored(ChangeRestoredListener.Event event) {
    ChangeInfo change = event.getChange();
    log.info("ffasffasdf");
    log.info(change.id);
    log.info("{}", event.getRevision().draft);
    try {
      automergeChanges(change, event.getRevision());
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}: {}", change.id, e);
    }
  }

  @Override
  public void onDraftPublished(DraftPublishedListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      automergeChanges(change, event.getRevision());
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}: {}", change.id, e);
    }
  }

  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event event) {
    ChangeInfo change = event.getChange();
    try {
      automergeChanges(change, event.getRevision());
    } catch (RestApiException | IOException e) {
      log.error("Failed to edit downstream topics of {}: {}", change.id, e);
    }
  }

  public void automergeChanges(ChangeInfo change, RevisionInfo revisionInfo)
      throws RestApiException, IOException {
    if (revisionInfo.draft != null && revisionInfo.draft) {
      log.info("Patchset {} is draft change, ignoring.",
          revisionInfo.commit.commit);
      return;
    }

    String currentRevision = revisionInfo.commit.commit;
    log.info("Handling patchsetevent with change id {} and revision {}",
        change.id, currentRevision);

    Set<String> downstreamBranches = config.getDownstreamBranches(
        change.branch, change.project);

    if (downstreamBranches.isEmpty()) {
      return;
    }

    // Map whether or not we should merge it or skip it for each downstream
    Map<String, Boolean> dsBranchMap = new HashMap<String, Boolean>();
    for (String downstreamBranch : downstreamBranches) {
      boolean isSkipMerge = config.isSkipMerge(change.branch,
          downstreamBranch, change.subject);
      dsBranchMap.put(downstreamBranch, !isSkipMerge);
    }
    log.info("Automerging change {} from branch {}", change.id, change.branch);

    ChangeApi currentChange = gApi.changes().id(change._number);
    String previousRevision = getPreviousRevision(currentChange,
        revisionInfo._number);

    createMergesAndHandleConflicts(dsBranchMap, change.id, change.project,
        change.topic, change.subject, previousRevision, currentRevision);
  }

  public void createMergesAndHandleConflicts(Map<String, Boolean> dsBranchMap,
                                                String id, String project,
                                                String topic, String subject,
                                                String obsoleteRevision,
                                                String currentRevision)
      throws RestApiException {
    ReviewInput reviewInput = new ReviewInput();
    Map<String, Short> labels = new HashMap<String, Short>();
    short vote = 0;
    try {
      createDownstreamMerges(dsBranchMap, id, project, topic, subject,
          obsoleteRevision, currentRevision);

      reviewInput.message =
          "Automerging to " + Joiner.on(", ").join(dsBranchMap.keySet())
              + " succeeded!";
      reviewInput.notify = NotifyHandling.NONE;
      vote = 1;
    } catch (FailedMergeException e) {
      reviewInput.message = e.displayConflicts();
      reviewInput.notify = NotifyHandling.ALL;
      vote = -1;
    }
    labels.put(config.getAutomergeLabel(), vote);
    reviewInput.labels = labels;
    gApi.changes().id(id).revision(currentRevision).review(reviewInput);
  }

  public void createDownstreamMerges(Map<String, Boolean> dsBranchMap,
                                        String id, String project, String topic,
                                        String subject, String obsoleteRevision,
                                        String currentRevision)
      throws RestApiException, FailedMergeException {
    Map<String, String> failedMerges = new HashMap<String, String>();

    List<Integer> existingDownstream;
    for (String downstreamBranch : dsBranchMap.keySet()) {
      // If there are existing downstream merges, abandon them.
      if (obsoleteRevision != null) {
        existingDownstream = getExistingDownstreamMerges(obsoleteRevision,
            topic, downstreamBranch);
        if (!existingDownstream.isEmpty()) {
          log.info("Prev downstreams exist: abandoning downstream of {} for {}",
              downstreamBranch, project);
          for (Integer downstreamChangeNumber : existingDownstream) {
            abandonChange(downstreamChangeNumber);
          }
        }
      }

      // Create all downstreams
      try {
        log.info("Attempting downstream merge of {} on branch {}",
            currentRevision, downstreamBranch);
        createDownstreamMerge(currentRevision, id, project, topic, subject,
            downstreamBranch, dsBranchMap.get(downstreamBranch));
      } catch (MergeConflictException e) {
        log.info("Merge conflict from {} to {}", currentRevision,
            downstreamBranch);
        failedMerges.put(downstreamBranch, e.getMessage());
      }
    }

    if (!failedMerges.keySet().isEmpty()) {
      throw new FailedMergeException(failedMerges);
    }
  }

  // method to get change ids of all single-hop downstream changes
  public List<Integer> getExistingDownstreamMerges(
      String upstreamRevision, String topic, String downstreamBranch)
      throws RestApiException {
    List<Integer> downstreamChangeNumbers = new ArrayList<Integer>();
    // get changes in same topic and check if their parent is upstreamRevision
    String query = "topic:" + topic + " status:open";
    List<ChangeInfo> changes = gApi.changes().query(query).withOptions(
        ListChangesOption.ALL_REVISIONS,
        ListChangesOption.CURRENT_COMMIT).get();

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

  public void createDownstreamMerge(String currentRevision, String id,
                                       String project, String topic,
                                       String subject, String downstreamBranch,
                                       boolean doMerge)
      throws RestApiException {
    String currentTopic = setTopic(id, topic);

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = currentRevision;

    log.info("Creating downstream merge for {}", currentRevision);
    ChangeInput downstreamChangeInput = new ChangeInput();
    downstreamChangeInput.project = project;
    downstreamChangeInput.branch = downstreamBranch;
    downstreamChangeInput.subject = subject + " am: " +
                                        currentRevision.substring(0, 10);
    downstreamChangeInput.topic = currentTopic;
    downstreamChangeInput.merge = mergeInput;

    if (!doMerge) {
      mergeInput.strategy = "ours";
      downstreamChangeInput.subject += " [skipped]";
      log.info("Skipping merge for {} to {}", currentRevision,
          downstreamBranch);
    }

    ChangeApi newChangeApi = gApi.changes().create(downstreamChangeInput);

    // Vote +2 on all downstream branches unless merge conflict.
    ReviewInput reviewInput = new ReviewInput();
    ChangeInfo newChange = newChangeApi.get(
        EnumSet.of(ListChangesOption.CURRENT_REVISION));
    short codeReviewVote = 2;
    Map<String, Short> labels = new HashMap<String, Short>();
    labels.put(config.getCodeReviewLabel(), codeReviewVote);
    reviewInput.labels = labels;
    gApi.changes().id(newChange.id)
                  .revision(newChange.currentRevision)
                  .review(reviewInput);
  }

  private String getPreviousRevision(
      ChangeApi change, int currentPatchSetNumber) throws RestApiException {
    String previousRevision = null;
    int maxPatchSetNum = 0;
    if (currentPatchSetNumber > 1) {
      // Get sha of patch set with highest number we can see
      Map<String, RevisionInfo> revisionMap = change.get(
          EnumSet.of(ListChangesOption.ALL_REVISIONS)).revisions;
      for (Map.Entry<String, RevisionInfo> revisionEntry :
          revisionMap.entrySet()) {
        int revisionPatchNumber = revisionEntry.getValue()._number;
        if (revisionPatchNumber > maxPatchSetNum &&
                revisionPatchNumber < currentPatchSetNumber) {
          previousRevision = revisionEntry.getKey();
          maxPatchSetNum = revisionPatchNumber;
        }
      }
    }
    return previousRevision;
  }

  private void abandonChange(Integer changeNumber) throws RestApiException {
    log.info("Abandoning change: {}", changeNumber);
    AbandonInput abandonInput = new AbandonInput();
    abandonInput.notify = NotifyHandling.NONE;
    abandonInput.message =
        "Merge parent updated; abandoning and recreating downstream automerge.";
    gApi.changes().id(changeNumber).abandon(abandonInput);
  }

  private String setTopic(String id, String topic) throws RestApiException {
    if (topic == null || topic.isEmpty()) {
      topic = "am-" + UUID.randomUUID().toString();
      log.info("Setting original change {} topic to {}", id, topic);
      gApi.changes().id(id).topic(topic);
    }
    return topic;
  }
}
