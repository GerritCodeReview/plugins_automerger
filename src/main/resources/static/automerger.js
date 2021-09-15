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

var currentChange;
var downstreamConfigMap;
Gerrit.install(function(self) {
    const restApi = self.restApi();
    const changeActions = self.changeActions();

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

        // Add checkboxes to box for each downstream branch
        var checkboxes = [];
        Object.keys(branchToCheckbox).forEach(function(branch) {
            checkboxes.push(branchToCheckbox[branch])
            checkboxes.push(c.br());
        });
        // Create actual merge button
        var b = createMergeButton(c, branchToCheckbox);
        var popupElements = checkboxes.concat(b);
        const div = document.createElement('div');
        for (const el of popupElements) {
          div.appendChild(el);
        }
        c.popup(div);
        return branchToCheckbox;
    }

    function createMergeButton(c, branchToCheckbox) {
        return c.button('Merge', {onclick: function(e){
            var branchMap = {};
            Object.keys(branchToCheckbox).forEach(function(key){
                branchMap[key] = branchToCheckbox[key].firstChild.checked;
            });
            // gerrit converts to camelcase on the java end
            c.call({'branch_map': branchMap},
                function(r){ c.refresh(); });
            e.currentTarget.setAttribute("disabled", true);
        }});
    }

    function styleRelatedChanges() {
        document.querySelectorAll('[data-branch]').forEach(function(relChange) {
            var relatedBranch = relChange.dataset.branch;
            if (relatedBranch == currentChange.branch) {
                relChange.style.fontWeight = 'bold';
            } else {
                relChange.style.fontWeight = '';
            }
            if (relChange.innerText.includes('[skipped')) {
                relChange.parentNode.style.backgroundColor = 'lightGray';
            }
        })
    }

    function getDownstreamConfigMap() {
        var changeId = currentChange._number;
        var revisionId = currentChange.current_revision;
        var url = `/changes/${changeId}/revisions/${revisionId}` +
                   `/automerger~config-downstream`;
        restApi.post(url, {'subject': currentChange.subject})
            .then((resp) => {
                downstreamConfigMap = resp;
                styleRelatedChanges();
            });
    }

    function onShowChange(e, revision) {
        currentChange = e;
        getDownstreamConfigMap();
        const detail = changeActions.getActionDetails('automerge-change');
        if (detail) {
            changeActions.addTapListener(detail.__key, () => {
              onAutomergeChange(
                new GrPluginActionContext(self, detail, e, revision)
              );
            });
        }
    }
    self.on('showchange', onShowChange);
});
