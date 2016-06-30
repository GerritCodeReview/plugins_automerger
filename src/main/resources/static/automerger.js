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
var downstreamConfigMap;
Gerrit.install(function(self) {

    function onAutomergeChange(c) {
        addCheckboxes(c, downstreamConfigMap);
    }

    function addCheckboxes(c, downstreamConfigMap) {
        var branchToCheckbox = {};
        var downstreamConfigBranches = Object.keys(downstreamConfigMap);
        // Initialize checkboxes for each downstream branch
        downstreamConfigBranches.forEach(function(branch) {
            var checkbox = c.checkbox();
            if (downstreamConfigMap[branch])
                checkbox.checked = true;
            branchToCheckbox[branch] = c.label(checkbox, branch);
        });

        //Add checkboxes to box for each downstream branch
        var checkboxes = [];
        Object.keys(branchToCheckbox).forEach(function(branch) {
            checkboxes.push(branchToCheckbox[branch])
            checkboxes.push(c.br());
        });
        // Create actual merge button
        var b = createMergeButton(c, branchToCheckbox);
        var popupElements = checkboxes.concat(b);
        c.popup(c.div.apply(this, popupElements));
        return branchToCheckbox;
    }

    function createMergeButton(c, branchToCheckbox) {
        return c.button('Merge', {onclick: function(){
            var branchMap = {};
            Object.keys(branchToCheckbox).forEach(function(key){
                branchMap[key] = branchToCheckbox[key].firstChild.checked;
            });
            // has to be underscore because gerrit converts to camelcase
            c.call({'branch_map': JSON.stringify(branchMap)},
                function(r){
                    reviewInput = {}
                    if (r == 'Success') {
                        reviewInput['message'] =
                            `Automerge downstream of ` +
                            `${currentChange.current_revision} succeeded!`;
                        reviewInput['notify'] = 'NONE';
                        // TODO(stephenli): get from config instead of hardcode
                        reviewInput['labels'] = {'Automerged': -0};
                    } else {
                        reviewInput['message'] = r;
                        reviewInput['notify'] = 'NONE';
                        // TODO(stephenli): get from config instead of hardcode
                        reviewInput['labels'] = {'Automerged': -1};
                    }
                    Gerrit.post(
                        `changes/${currentChange.id}/revisions/` +
                        `${currentChange.current_revision}/review`,
                        reviewInput, function(r){});
                    Gerrit.refresh();
            });
        }});
    }

//    function boldBranch(branch) {
//        var style = document.createElement("style");
//        style.innerText = `[data-branch=${branch}] { font-weight: bold}`;
//        var selector = `[data-branch=${branch}]`;
//        document.querySelector(selector).appendChild(style);
//    }

    function getDownstreamConfigMap() {
        var changeId = currentChange.id;
        var revisionId = currentChange.current_revision;
        var url = `/changes/${changeId}/revisions/${revisionId}` +
                   `/automerger~config-downstream`;
        Gerrit.post(
            url, {'subject': currentChange.subject},
            function(resp) {
                downstreamConfigMap = JSON.parse(resp);
            });
    }

    function onShowChange(e) {
        currentChange = e;
        getDownstreamConfigMap();
//        boldBranch(currentChange.branch);
    }

    self.onAction('revision', 'automerge-change', onAutomergeChange);
    Gerrit.on('showchange', onShowChange);
});