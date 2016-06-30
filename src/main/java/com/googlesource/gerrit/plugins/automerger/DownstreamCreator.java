package com.googlesource.gerrit.plugins.automerger;

import com.google.common.base.Joiner;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.gerrit.server.events.TopicChangedEvent;
import com.google.inject.Inject;
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
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class DownstreamCreator implements EventListener {
  private static final Logger log = LoggerFactory.getLogger(DownstreamCreator.class);

  @Inject
  protected GerritApi gApi;

  @Override
  public void onEvent(Event event) {
    try {
      String eventType = event.getType();
      log.info("Event detected: " + eventType);
      if (eventType.equals("patchset-created")) {
        handlePatchsetCreated((PatchSetEvent) event);
      } else if (eventType.equals("change-abandoned")) {
        handleChangeAbandoned((ChangeAbandonedEvent) event);
      } else if (eventType.equals("topic-changed")) {
        handleTopicChanged((TopicChangedEvent) event);
      }
    } catch (RestApiException e) {
      log.error("REST API exception!", e);
    } catch (IOException e) {
      log.error("Failed to read config.", e);
    }
  }

  private void handleChangeAbandoned(ChangeAbandonedEvent changeAbandonedEvent) throws RestApiException {
    ChangeAttribute change = changeAbandonedEvent.change.get();
    String revision = changeAbandonedEvent.patchSet.get().revision;
    List<Integer> existingDownstream = getExistingDownstreamMerges(revision, change.topic,
            change.branch);
    log.info("Abandoning downstream of {} with topic {}: {}", revision, change.topic, existingDownstream);
    for (Integer changeNumber : existingDownstream) {
      abandonChange(changeNumber);
    }
  }

  private void handleTopicChanged(TopicChangedEvent topicChangedEvent) throws RestApiException {
    ChangeAttribute change = topicChangedEvent.change.get();
    String oldTopic = topicChangedEvent.oldTopic;
    String revision = gApi.changes().id(change.id).get(
            EnumSet.of(ListChangesOption.CURRENT_REVISION)).currentRevision;
    List<Integer> existingDownstream = getExistingDownstreamMerges(revision, oldTopic,
            change.branch);
    log.info("Setting topic {} on {}", change.topic, existingDownstream);
    for (Integer changeNumber : existingDownstream) {
      log.info("Changed {} from topic {} to {}", changeNumber, oldTopic, change.topic);
      gApi.changes().id(changeNumber).topic(change.topic);
    }
  }

  private void handlePatchsetCreated(PatchSetEvent patchSetEvent) throws RestApiException, IOException {
    ChangeAttribute change = patchSetEvent.change.get();
    String currentRevision = patchSetEvent.patchSet.get().revision;
    int patchSetNumber = Integer.parseInt(patchSetEvent.patchSet.get().number);
    log.info("Change id: " + change.id);

    ChangeApi currentChange = gApi.changes().id(change.number);

    String previousRevision = getPreviousRevision(currentChange, patchSetNumber);
    boolean isSkipMerge = false;

    ConfigLoader config = new ConfigLoader(gApi);
    config.loadConfig();
    log.info("config option keys:  " + Joiner.on(", ").join(config.configOptionKeys));

    Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project);

    if (downstreamBranches.isEmpty()) {
      return;
    }

    if (previousRevision == null) {
      for (String downstreamBranch : downstreamBranches) {
        log.info("No previous revisions: creating downstream merge of {} on branch {}",
                currentRevision, downstreamBranch);
        isSkipMerge = config.isSkipMerge(change.branch, downstreamBranch, change.commitMessage);
        createDownstreamMerge(currentRevision, change, downstreamBranch, isSkipMerge);
      }
      return;
    }

    // Previous revision exists, this is a patch set update.
    for (String downstreamBranch : downstreamBranches) {
      List<Integer> existingDownstream = getExistingDownstreamMerges(previousRevision, change.topic,
              downstreamBranch);

      // Create immediate downstreams if they don't exist, abandon/recreate if they do.
      if (existingDownstream.isEmpty()) {
        log.info("No downstreams exist: creating downstream merge of {} on branch {}",
                currentRevision, downstreamBranch);
        isSkipMerge = config.isSkipMerge(change.branch, downstreamBranch, change.commitMessage);
        createDownstreamMerge(currentRevision, change, downstreamBranch, isSkipMerge);
      } else {
        log.info("Previous downstreams exist: abandoning/recreating all downstream of {} for {}",
                downstreamBranch, change.project);
        for (Integer downstreamChangeNumber : existingDownstream) {
          abandonChange(downstreamChangeNumber);
          log.info("Creating downstream merge of {} on {} with changeId {}",
                  currentRevision, downstreamBranch, downstreamChangeNumber);
          isSkipMerge = config.isSkipMerge(change.branch, downstreamBranch, change.commitMessage);
          createDownstreamMerge(currentRevision, change, downstreamBranch, isSkipMerge);
        }
      }
    }
  }

  private String getPreviousRevision(ChangeApi change, int currentPatchSetNumber) throws RestApiException {
    String previousRevision = null;
    int maxPatchSetNum = 0;
    if (currentPatchSetNumber > 1) {
      // Get sha of patch set with highest number we can see
      // patchSetNumber - 1 won't work because it might be a change we can't see (i.e. draft)
      Map<String, RevisionInfo> revisionMap = change.get(
              EnumSet.of(ListChangesOption.ALL_REVISIONS)).revisions;
      for (Map.Entry<String, RevisionInfo> revisionEntry : revisionMap.entrySet()) {
        int revisionPatchNumber = revisionEntry.getValue()._number;
        if (revisionPatchNumber > maxPatchSetNum && revisionPatchNumber < currentPatchSetNumber) {
          previousRevision = revisionEntry.getKey();
          maxPatchSetNum = revisionPatchNumber;
        }
      }
    }
    log.info("Previous revision: {} ", previousRevision);
    return previousRevision;
  }

  private void abandonChange(Integer changeNumber) throws RestApiException {
    log.info("Abandoning change: {}", changeNumber);
    AbandonInput abandonInput = new AbandonInput();
    abandonInput.notify = NotifyHandling.NONE;
    abandonInput.message = "Merge parent updated; abandoning and recreating downstream automerge.";
    gApi.changes().id(changeNumber).abandon(abandonInput);
  }

  private ChangeApi createDownstreamMerge(String currentRevision, ChangeAttribute change,
                                           String downstreamBranch, boolean skipMerge) throws RestApiException {
    String topic = setTopic(change);

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = currentRevision;
    if (skipMerge) {
      mergeInput.strategy = "ours";
    }

    ChangeInput downstreamChangeInput = new ChangeInput();
    downstreamChangeInput.project = change.project;
    downstreamChangeInput.branch = downstreamBranch;
    downstreamChangeInput.subject = change.subject + " am: " + currentRevision.substring(0, 10);
    downstreamChangeInput.topic = topic;
    downstreamChangeInput.merge = mergeInput;
    log.info("Creating downstream merge for {}", currentRevision);

    return gApi.changes().create(downstreamChangeInput);
  }

  // method to get change ids of all single-downstream changes
  private List<Integer> getExistingDownstreamMerges(String upstreamRevision, String topic,
                                                   String downstreamBranch) throws RestApiException {
    List<Integer> downstreamChangeNumbers = new ArrayList<Integer>();
    // get changes in same topic and check if their parent is upstreamRevision
    String query = "topic:" + topic + " status:open";
    List<ChangeInfo> changes = gApi.changes().query(query).withOptions(
            ListChangesOption.ALL_REVISIONS, ListChangesOption.CURRENT_COMMIT).get();

    log.info("Num changes in same topic: {}", changes.size());
    for (ChangeInfo change : changes) {
      String changeRevision = change.currentRevision;
      log.info("Checking {}", changeRevision);
      RevisionInfo revision = change.revisions.get(changeRevision);
      List<CommitInfo> parents = revision.commit.parents;
      if (parents.size() > 1) {
        String secondParent = parents.get(1).commit;
        log.info("Checking first parent of {}: {} equals {}", changeRevision, secondParent, upstreamRevision);
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
