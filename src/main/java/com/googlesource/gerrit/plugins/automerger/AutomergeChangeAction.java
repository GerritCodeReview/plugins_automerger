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

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.events.EventFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

class AutomergeChangeAction
    implements UiAction<RevisionResource>,
                   RestModifyView<RevisionResource, AutomergeChangeAction
                                                        .Input> {
  private static final Logger log = LoggerFactory.getLogger(
      AutomergeChangeAction.class);

  private Provider<CurrentUser> user;
  private ConfigLoader config;
  private DownstreamCreator dsCreator;
  private EventFactory eventFactory;

  static class Input {
    String branchMap;
  }

  @Inject
  AutomergeChangeAction(Provider<CurrentUser> user, ConfigLoader config,
                           DownstreamCreator dsCreator,
                           EventFactory eventFactory) {
    this.user = user;
    this.config = config;
    this.dsCreator = dsCreator;
    this.eventFactory = eventFactory;
  }

  @Override
  public Object apply(RevisionResource rev, Input input)
      throws RestApiException, FailedMergeException {
    Gson g = new Gson();
    Type collectionType = new TypeToken<Map<String, Boolean>>() {
    }.getType();
    Map<String, Boolean> branchMap = g.fromJson(
        input.branchMap, collectionType);

    Change change = rev.getChange();
    String revision = rev.getPatchSet().getRevision().get();

    dsCreator.createMergesAndHandleConflicts(branchMap, change.getKey().get(),
        change.getProject().get(), change.getTopic(), change.getSubject(),
        revision, revision);
    return Response.none();
  }

  @Override
  public Description getDescription(RevisionResource resource) {
    String project = resource.getProject().get();
    String branch = resource.getChange().getDest().getShortName();
    Description desc = new Description();
    desc = desc.setLabel("Recreate automerges")
               .setTitle("Recreate automerges downstream");
    try {
      if (config.getDownstreamBranches(branch, project).isEmpty()) {
        desc = desc.setVisible(false);
      } else {
        desc = desc.setVisible(user.get() instanceof IdentifiedUser);
      }
    } catch (RestApiException e) {
      desc = desc.setVisible(false);
    } catch (IOException e) {
      desc = desc.setVisible(false);
    }
    return desc;
  }
}