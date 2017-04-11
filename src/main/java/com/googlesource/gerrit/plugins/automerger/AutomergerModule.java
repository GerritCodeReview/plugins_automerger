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

import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;;
import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.CommentAddedListener;
import com.google.gerrit.extensions.events.DraftPublishedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.inject.AbstractModule;

/** Module to bind listeners, plugins, and other modules. */
public class AutomergerModule extends AbstractModule {

  @Override
  protected void configure() {
    DynamicSet.bind(binder(), CommentAddedListener.class).to(DownstreamCreator.class);
    DynamicSet.bind(binder(), ChangeAbandonedListener.class).to(DownstreamCreator.class);
    DynamicSet.bind(binder(), ChangeRestoredListener.class).to(DownstreamCreator.class);
    DynamicSet.bind(binder(), DraftPublishedListener.class).to(DownstreamCreator.class);
    DynamicSet.bind(binder(), RevisionCreatedListener.class).to(DownstreamCreator.class);
    DynamicSet.bind(binder(), TopicEditedListener.class).to(DownstreamCreator.class);
    DynamicSet.bind(binder(), MergeValidationListener.class).to(MergeValidator.class);
    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            post(REVISION_KIND, "automerge-change").to(AutomergeChangeAction.class);
            post(CHANGE_KIND, "config-downstream").to(ConfigDownstreamAction.class);
          }
        });
    DynamicSet.bind(binder(), WebUiPlugin.class).toInstance(new JavaScriptPlugin("automerger.js"));
  }
}
