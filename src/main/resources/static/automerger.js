// Copyright (C) 2015 The Android Open Source Project
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

var currentChange;
Gerrit.install(function(self) {
    function abandonChange(changeId) {
        var abandonInput = {
          "message": `Automerger abandoning and recreating due to \
                      ${Gerrit.getCurrentUser().email} clicking skip in UI`,
          "notify": "NONE"
        }
        Gerrit.post(`/changes/${changeId}/abandon`, abandonInput,
            function(r){});
    }

    function skipChange(change, secondParent) {
        var mergeInput = {
            "source": secondParent.commit,
            "strategy": "ours"
        }
        var changeInput = {
            "subject" : secondParent.subject + " am: "
                + change.current_revision + " -s ours",
            "project" : change.project,
            "branch" : change.branch,
            "topic" : change.topic,
            "merge": mergeInput
        }

        Gerrit.post('/changes/', changeInput, function(r){
            Gerrit.refresh();
        });
    }

    function skipDownstream() {
        var query = `/changes/?q=topic:${currentChange.topic} \
                     status:open&o=CURRENT_REVISION&o=CURRENT_COMMIT`
        Gerrit.get(query, function(changes){
            for (var index in changes) {
                var candidateChange = changes[index];
                var currentRevision = candidateChange.current_revision;
                var secondParent = candidateChange.revisions[currentRevision]
                                   .commit.parents[1];
                if (secondParent &&
                    secondParent.commit == currentChange.current_revision) {
                    abandonChange(candidateChange.id);
                    skipChange(candidateChange, secondParent);
                }
            }
        });
    }

    function hasDownstream() {
        var allVotes = currentChange.labels.Automerged.all;
        return (allVotes &&
                allVotes.map(function(vote){return vote.value}).includes(1));
    }

    function addHeaderButton(buttonText, title, callback) {
        var button = document.createElement("button");
        button.type = "button";
        button.title = title;
        button.addEventListener("click", callback);
        var div = document.createElement("div");
        div.innerText = buttonText;
        button.appendChild(div);
        var selector = '.com-google-gerrit-client-change-' +
                       'ChangeScreen_BinderImpl_GenCss_style-' +
                       'infoLineHeaderButtons';
        var info = document.querySelector(selector);
        info.appendChild(button);
    }

    function boldBranch(branch) {
        var style = document.createElement("style");
        style.innerText = `[data-branch=${branch}] { font-weight: bold}`;
        var selector = '.com-google-gerrit-client-change-RelatedChanges-' +
                       'RelatedChangesCss-tabPanel';
        document.querySelector(selector).appendChild(style);
    }

    function onShowChange(e) {
        currentChange = e;
        boldBranch(currentChange.branch);
        if (hasDownstream()) {
            addHeaderButton("Skip downstream",
                            "Finds changes in topic, with current commit " +
                            "as second parent, and skips them", skipDownstream);
        }
    }

    Gerrit.on('showchange', onShowChange);
});