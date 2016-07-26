package com.googlesource.gerrit.plugins.automerger;

import com.google.common.base.Joiner;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.api.GerritApi;
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
import com.google.gerrit.server.events.PatchSetEvent;
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
        PatchSetEvent patchSetEvent = (PatchSetEvent) event;
        ChangeAttribute change = patchSetEvent.change.get();
        String currentRevision = patchSetEvent.patchSet.get().revision;
        int patchSetNumber = Integer.parseInt(patchSetEvent.patchSet.get().number);
        log.info("Change id: " + change.id);
        log.info("Change number: " + change.number);
        log.info("Change project: " + change.project);
        log.info("Change branch: " + change.branch);
        log.info("Change current revision: " + currentRevision);
        log.info("Change patchsets: " + change.patchSets);
        log.info("Change topic: " + change.topic);
        log.info("Patchset number: " + patchSetNumber);
        log.info("Change subject: " + change.subject);


        ChangeApi currentChange = gApi.changes().id(change.number);
        log.info("got api of change id: " + change.id);

        String previousRevision = null;
        int maxPatchSetNum = 0;
        if (patchSetNumber > 1) {
          // Get sha of patch set with highest number we can see
          // patchSetNumber - 1 won't work because it might be a change we can't see (i.e. draft)
          Map<String, RevisionInfo> revisionMap = currentChange.get(
                  EnumSet.of(ListChangesOption.ALL_REVISIONS)).revisions;
          for (Map.Entry<String, RevisionInfo> revisionEntry : revisionMap.entrySet()) {
            if (revisionEntry.getValue()._number > maxPatchSetNum) {
              previousRevision = revisionEntry.getKey();
            }
          }
          log.info("Previous revision is: " + previousRevision);
//          for (RevisionInfo revisionInfo : revisionMap.values()) {
//            if (revisionInfo._number > maxPatchSetNum) {
//              previousRevision = revisionInfo.commit;
//            }
//          }
        }

        ConfigLoader config = new ConfigLoader(gApi);
        config.loadConfig();
        log.info("config option keys:  " + Joiner.on(", ").join(config.configOptionKeys));

        Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project, false);
        if (previousRevision != null) {
          // TODO(stephenli): set value of merge_all here based on whether it matches DO NOT MERGE
          for (String downstreamBranch : downstreamBranches) {
            List<String> existingDownstream = getExistingDownstreamMerges(previousRevision, change.topic,
                    downstreamBranch);

            if (existingDownstream.isEmpty()) {
              log.info("No downstreams exist: creating downstream merge of {} on branch {}",
                      currentRevision, downstreamBranch);
              createDownstreamMerge(currentRevision, change, downstreamBranch);
            } else {
              // Upload a new patchset to downstreams
              for (String downstreamChangeId : existingDownstream) {
                log.info("Downstreams exist: creating downstream merge of {} on {} with changeId {}",
                        currentRevision, downstreamBranch, downstreamChangeId);
                createDownstreamMerge(currentRevision, change, downstreamBranch, downstreamChangeId);
              }
            }
          }
        } else {
          for (String downstreamBranch : downstreamBranches) {
            log.info("No previous revisions: creating downstream merge of {} on branch {}",
                    currentRevision, downstreamBranch);
            createDownstreamMerge(currentRevision, change, downstreamBranch);
          }
        }
      }
    } catch (RestApiException e) {
      log.error("REST API exception!", e);
    } catch (IOException e) {
      log.error("Failed to read config.", e);
    }
  }

  private ChangeApi createDownstreamMerge(String currentRevision, ChangeAttribute change,
                                          String downstreamBranch) throws RestApiException {
    return createDownstreamMerge(currentRevision, change, downstreamBranch, null);
  }

  private ChangeApi createDownstreamMerge(String currentRevision, ChangeAttribute change,
                                           String downstreamBranch, String changeId) throws RestApiException {
    String topic = setTopic(change);

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = currentRevision;
    // TODO(stephenli): mergeInput.strategy = "ours" when appropriate

    ChangeInput downstreamChangeInput = new ChangeInput();
    downstreamChangeInput.project = change.project;
    downstreamChangeInput.branch = downstreamBranch;
    downstreamChangeInput.subject = change.subject + " am: " + currentRevision.substring(0, 10);
    downstreamChangeInput.topic = topic;
    downstreamChangeInput.merge = mergeInput;
    if (changeId != null) {
      // TODO(stephenli): get this to update the original damn change!!
//      downstreamChangeInput.subject = downstreamChangeInput.subject + "\n\nChange-Id: " + changeId;
//      downstreamChangeInput.baseChange = changeId;
//      log.info("Base change set to {}", changeId);
    }
    log.info("Creating downstream merge for {}", currentRevision);

    return gApi.changes().create(downstreamChangeInput);
  }

  // method to get change ids of all single-downstream changes
  private List<String> getExistingDownstreamMerges(String upstreamRevision, String topic,
                                                   String downstreamBranch) throws RestApiException {
    List<String> downstreamChanges = new ArrayList<String>();
    // get changes in same topic and check if their parent is upstreamRevision
    String query = "topic:" + topic;
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
        log.info("Detected second parent of {}: {}", changeRevision, secondParent);
        if (secondParent.equals(upstreamRevision)) {
          downstreamChanges.add(change.changeId);
        }
      }
    }
    return downstreamChanges;
  }

  private String setTopic(ChangeAttribute change) throws RestApiException {
    String topic = change.topic;
    if (topic == null || topic.isEmpty()) {
      // TODO(stephenli): randomize the topic here if there are downstreams
      topic = UUID.randomUUID().toString();
      log.info("Setting original change {} topic to {}", change.id, topic);
      gApi.changes().id(change.id).topic(topic);
    }
    return topic;
  }

  // TODO(stephenli): change all downstreams if main topic is changed
}
