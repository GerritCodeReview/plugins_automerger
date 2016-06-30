package com.googlesource.gerrit.plugins.automerger;

import com.google.common.base.Joiner;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
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
import java.util.Map;
import java.util.Set;

public class DownstreamCreator implements EventListener {
  private static final Logger log = LoggerFactory.getLogger(DownstreamCreator.class);

  @Inject
  protected GerritApi gApi;

  @Override
  public void onEvent(Event event) {
    try {
      String eventType = event.getType();
      log.info("Event detected asdffdsa: " + eventType);
      // TODO(stephenli): get types of each event, then cast it so i can get info about it
      // TODO(stephenli): use enum? or constant?
      if (eventType.equals("patchset-created")) {
        PatchSetEvent patchSetEvent = (PatchSetEvent) event;
        ChangeAttribute change = patchSetEvent.change.get();
        log.info("Change id: " + change.id);
        log.info("Change number: " + change.number);
        log.info("Change project: " + change.project);
        log.info("Change branch: " + change.branch);
        log.info("Change current revision: " + patchSetEvent.patchSet.get().revision);
        log.info("Change topic: " + change.topic);
        log.info("Patchset number: " + patchSetEvent.patchSet.get().number);


        ChangeApi currentChange = gApi.changes().id(change.number);
        log.info("got api of change id: " + change.id);

        ConfigLoader config = new ConfigLoader(gApi);
        config.loadConfig();

        // TODO(stephenli): set value of merge_all here based on whether it matches DO NOT MERGE
        Set<String> downstreamBranches = config.getDownstreamBranches(change.branch, change.project, false);
        String downstreamBranchesString = Joiner.on(", ").join(downstreamBranches);
        log.info("Downstream branches string: " + downstreamBranchesString);

        // TODO(stephenli): double-check how this works with +2s and trivial changes
        ReviewInput reviewMessage = new ReviewInput();
        reviewMessage.message = "Change will be automerged to the following branches: " + downstreamBranchesString;
        reviewMessage.notify = ReviewInput.NotifyHandling.NONE;
        currentChange.current().review(reviewMessage);
//        for (String downstreamBranch : downstreamBranches) {
//          // TODO(stephenli): if already exists, update it instead
//
//
//          CherryPickInput cherryPickInput = new CherryPickInput();
//          cherryPickInput.message = "auto-cherry-picking yay";
//          cherryPickInput.destination = downstreamBranch;
//          ChangeApi downstreamChange = currentChange.current().cherryPick(cherryPickInput);
//          downstreamChange.topic(currentChange.topic());
//        }


      }
    } catch (RestApiException e) {
      log.error("REST API exception!", e);
    } catch (IOException e) {
      log.error("Failed to read config.", e);
    }
  }
}
