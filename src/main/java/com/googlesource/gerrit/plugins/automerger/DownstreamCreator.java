package com.googlesource.gerrit.plugins.automerger;

import com.google.common.base.Joiner;
import com.google.gerrit.common.EventListener;
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
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
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


class FailedMergeException extends Exception {
  public Set<String> failedMerges;

  FailedMergeException(Set<String> failedMerges) {
    this.failedMerges = failedMerges;
  }

  public String joinedFailedMerges() {
    return Joiner.on(", ").join(failedMerges);
  }
}


@Singleton
public class DownstreamCreator implements EventListener {
  private static final Logger log = LoggerFactory.getLogger(
      DownstreamCreator.class);

  protected GerritApi gApi;
  private ConfigLoader config;

  @Inject
  public DownstreamCreator(GerritApi gApi) {
    this.gApi = gApi;
    try {
      config = new ConfigLoader(gApi);
      config.loadConfig();
    } catch (IOException e) {
      log.error("Failed to load config.");
    }
  }

  @Override
  public void onEvent(Event event) {
    try {
      String eventType = event.getType();
      log.info("Event detected: " + eventType);
      switch (eventType) {
        case "patchset-created":
          handlePatchsetCreated((PatchSetEvent) event);
          break;
        case "change-abandoned":
          handleChangeAbandoned((ChangeAbandonedEvent) event);
          break;
        case "topic-changed":
          handleTopicChanged((TopicChangedEvent) event);
          break;
        case "change-merged":
          handleChangeMerged((ChangeMergedEvent) event);
          break;
      }
    } catch (RestApiException e) {
      log.error("REST API exception!", e);
    } catch (IOException e) {
      log.error("Failed to read config.", e);
    }
  }

  private void handleChangeMerged(ChangeMergedEvent changeMergedEvent)
      throws IOException {
    ChangeAttribute change = changeMergedEvent.change.get();
    if (change.project.equals(config.configProject) &&
        change.branch.equals(config.configProjectBranch)) {
      config.loadConfig();
    }
  }

  private void handleChangeAbandoned(ChangeAbandonedEvent changeAbandonedEvent)
      throws RestApiException {
    ChangeAttribute change = changeAbandonedEvent.change.get();
    String revision = changeAbandonedEvent.patchSet.get().revision;
    List<Integer> existingDownstream = getExistingDownstreamMerges(revision,
        change.topic, change.branch);
    for (Integer changeNumber : existingDownstream) {
      log.info("Abandoning downstream of {}: {}", revision, changeNumber);
      abandonChange(changeNumber);
    }
  }

  private void handleTopicChanged(TopicChangedEvent topicChangedEvent)
      throws RestApiException {
    ChangeAttribute change = topicChangedEvent.change.get();
    String oldTopic = topicChangedEvent.oldTopic;
    String revision = gApi.changes().id(change.id).get(
        EnumSet.of(ListChangesOption.CURRENT_REVISION)).currentRevision;
    List<Integer> existingDownstream = getExistingDownstreamMerges(revision,
        oldTopic, change.branch);
    for (Integer changeNumber : existingDownstream) {
      log.info("Setting topic {} on {}", change.topic, changeNumber);
      gApi.changes().id(changeNumber).topic(change.topic);
    }
  }

  private void handlePatchsetCreated(PatchSetEvent patchSetEvent)
      throws RestApiException, IOException {
    ChangeAttribute change = patchSetEvent.change.get();
    String currentRevision = patchSetEvent.patchSet.get().revision;
    int patchSetNumber = Integer.parseInt(patchSetEvent.patchSet.get().number);
    log.info("Handling patchsetevent with change id {} and revision {}",
        change.id, currentRevision);

    Set<String> downstreamBranches = config.getDownstreamBranches(
        change.branch, change.project);

    if (downstreamBranches.isEmpty()) {
      return;
    }
    log.info("Automerging change: {}", currentRevision);

    ChangeApi currentChange = gApi.changes().id(change.number);
    String previousRevision = getPreviousRevision(currentChange,
        patchSetNumber);

    ReviewInput reviewInput = new ReviewInput();
    Map<String, Short> labels = new HashMap<String, Short>();
    short vote = 1;
    try {
      createDownstreamMerges(downstreamBranches, change,
          previousRevision, currentRevision);
      reviewInput.message = "Automerge downstream of " +
                                currentRevision + " succeeded!";
      reviewInput.notify = NotifyHandling.NONE;
    } catch (FailedMergeException e) {
      reviewInput.message =
          "Automerge downstream of " + currentRevision +
              " had a merge conflict when merging to " +
              e.joinedFailedMerges() + ". Please follow instructions at " +
              "go/resolveconflict to resolve this conflict.";
      reviewInput.notify = NotifyHandling.ALL;
      vote = -1;
    }
    labels.put(config.getAutomergeLabel(), vote);
    reviewInput.labels = labels;
    gApi.changes().id(change.id).revision(currentRevision).review(reviewInput);
  }

  private void createDownstreamMerges(
      Set<String> downstreamBranches, ChangeAttribute change,
      String previousRevision, String currentRevision)
      throws RestApiException, FailedMergeException {
    Set<String> failedMerges = new HashSet<String>();

    List<Integer> existingDownstream;
    for (String downstreamBranch : downstreamBranches) {
      // If there are existing downstream merges, abandon them.
      if (previousRevision != null) {
        existingDownstream = getExistingDownstreamMerges(previousRevision,
            change.topic, downstreamBranch);
        if (!existingDownstream.isEmpty()) {
          log.info("Prev downstreams exist: abandoning downstream of {} for {}",
              downstreamBranch, change.project);
          for (Integer downstreamChangeNumber : existingDownstream) {
            abandonChange(downstreamChangeNumber);
          }
        }
      }

      // Create all downstreams
      try {
        log.info("Attempting downstream merge of {} on branch {}",
            currentRevision, downstreamBranch);
        boolean isSkipMerge = config.isSkipMerge(change.branch,
            downstreamBranch, change.commitMessage);
        createDownstreamMerge(currentRevision, change, downstreamBranch,
            isSkipMerge);
      } catch (MergeConflictException e) {
        log.info("Merge conflict from {} to {}", currentRevision,
            downstreamBranch);
        failedMerges.add(downstreamBranch);
      }
    }

    if (!failedMerges.isEmpty()) {
      throw new FailedMergeException(failedMerges);
    }
  }

  private void createDownstreamMerge(
      String currentRevision, ChangeAttribute change, String downstreamBranch,
      boolean skipMerge) throws RestApiException {
    String topic = setTopic(change);

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = currentRevision;

    log.info("Creating downstream merge for {}", currentRevision);
    ChangeInput downstreamChangeInput = new ChangeInput();
    downstreamChangeInput.project = change.project;
    downstreamChangeInput.branch = downstreamBranch;
    downstreamChangeInput.subject = change.subject + " am: " +
                                        currentRevision.substring(0, 10);
    downstreamChangeInput.topic = topic;
    downstreamChangeInput.merge = mergeInput;

    if (skipMerge) {
      mergeInput.strategy = "ours";
      downstreamChangeInput.subject += " -s ours";
      log.info("Skipping merge for {} to {}", currentRevision,
          downstreamBranch);
    }

    gApi.changes().create(downstreamChangeInput);
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

  // method to get change ids of all single-hop downstream changes
  private List<Integer> getExistingDownstreamMerges(
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

  private String setTopic(ChangeAttribute change) throws RestApiException {
    String topic = change.topic;
    if (topic == null || topic.isEmpty()) {
      topic = "am-" + UUID.randomUUID().toString();
      log.info("Setting original change {} topic to {}", change.id, topic);
      gApi.changes().id(change.id).topic(topic);
    }
    return topic;
  }
}
