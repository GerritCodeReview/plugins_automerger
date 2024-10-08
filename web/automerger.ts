/**
 * @license
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import {
  ActionInfo,
  ChangeInfo,
} from '@gerritcodereview/typescript-api/rest-api';
import {PopupPluginApi} from '@gerritcodereview/typescript-api/popup';
import {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import {RequestPayload} from '@gerritcodereview/typescript-api/rest';

// export for testing only
export type ConfigMap = {[branch: string]: boolean};

export declare interface UIActionInfo extends ActionInfo {
  __key: string;
  __url?: string;
}

export interface PopupPluginApiExtended extends PopupPluginApi {
  // TODO: Remove this reference to a private method. This can break any time.
  _getElement: () => HTMLElement;
}

/**
 * Error callback that throws an error.
 *
 * Pass into REST API methods as errFn to make the returned Promises reject on
 * error.
 *
 * If error is provided, it's thrown.
 * Otherwise if response with error is provided the promise that will throw an
 * error is returned.
 */
export function throwingErrorCallback(
  response?: Response | null,
  err?: Error
): void | Promise<void> {
  if (err) throw err;
  if (!response) return;

  return response.text().then(errorText => {
    let message = `Error ${response.status}`;
    if (response.statusText) {
      message += ` (${response.statusText})`;
    }
    if (errorText) {
      message += `: ${errorText}`;
    }
    throw new Error(message);
  });
}

export class Automerger {
  private change?: ChangeInfo;

  private action?: UIActionInfo;

  private downstreamConfigMap: ConfigMap = {};

  private mergeMode: string = "";

  readonly plugin: PluginApi;

  constructor(readonly p: PluginApi) {
    this.plugin = p;
  }

  private callAction(payload: RequestPayload, onSuccess: () => void) {
    if (!this.action?.method) return;
    if (!this.action?.__url) return;
    this.plugin
      .restApi()
      .fetch(
        this.action.method,
        this.action.__url,
        payload,
        throwingErrorCallback)
      .then(onSuccess)
      .catch((error: unknown) => {
        document.dispatchEvent(
          new CustomEvent('show-alert', {
            detail: {message: `Plugin error: ${error}`},
          })
        );
      });
  }

  private onAutomergeChange() {
    // Create checkboxes for each downstream branch.
    const branchToCheckbox: {[branch: string]: HTMLElement} = {};
    const downstreamConfigBranches = Object.keys(this.downstreamConfigMap);
    downstreamConfigBranches.forEach(branch => {
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      if (this.downstreamConfigMap[branch]) checkbox.checked = true;
      const label = document.createElement('gr-label');
      label.appendChild(document.createTextNode(branch));
      const div = document.createElement('div');
      div.appendChild(checkbox);
      div.appendChild(label);
      branchToCheckbox[branch] = div;
    });

    // Create popup content.
    const popupContent = document.createElement('div');
    for (const branch of Object.keys(branchToCheckbox)) {
      popupContent.appendChild(branchToCheckbox[branch]);
      popupContent.appendChild(document.createElement('br'));
    }
    popupContent.appendChild(this.createMergeButton(branchToCheckbox));

    this.plugin.popup().then((popApi: PopupPluginApi) => {
      const popupEl = (popApi as PopupPluginApiExtended)._getElement();
      if (!popupEl) throw new Error('Popup element not found');
      popupEl.appendChild(popupContent);
    });
  }

  private createMergeButton(branchToCheckbox: {[branch: string]: HTMLElement}) {
    const onClick = (e: Event) => {
      const branchMap: {[branch: string]: boolean} = {};
      for (const branch of Object.keys(branchToCheckbox)) {
        branchMap[branch] =
          (branchToCheckbox[branch].firstChild as HTMLInputElement | undefined)
            ?.checked ?? false;
      }
      this.callAction({branch_map: branchMap}, () => {
        this.windowReload();
      });
      const target = e.currentTarget;
      if (target && target instanceof Element) {
        target.setAttribute('disabled', 'true');
      }
    };
    const button = document.createElement('gr-button');
    button.appendChild(document.createTextNode(this.mergeMode));
    button.addEventListener('click', onClick);
    return button;
  }

  // public for testing only
  windowReload() {
    window.location.reload();
  }

  private styleRelatedChanges() {
    document.querySelectorAll('[data-branch]').forEach(relChange => {
      if (!(relChange instanceof HTMLElement)) return;
      if (!this.change) return;
      const relatedBranch = relChange.dataset['branch'];
      if (relatedBranch === this.change.branch) {
        relChange.style.fontWeight = 'bold';
      } else {
        relChange.style.fontWeight = '';
      }
      if (relChange.innerText.includes('[skipped')) {
        const parent = relChange.parentNode;
        if (parent && parent instanceof HTMLElement) {
          parent.style.backgroundColor = 'lightGray';
        }
      }
    });
  }

  private getDownstreamConfigMap() {
    const change = this.change;
    if (!change) return;

    const changeId = change._number;
    if(changeId === undefined) return;

    const revisionId = change.current_revision;
    if(revisionId === undefined) return;

    const url =
      `/changes/${changeId}/revisions/${revisionId}` +
      '/automerger~config-downstream';
    this.plugin.restApi().post<ConfigMap>(url, {subject: change.subject}).then(resp => {
      this.downstreamConfigMap = resp;
      this.styleRelatedChanges();
    });
  }

  private getMode() {
    const url = `/config/server/automerger~automerge-mode`;
    this.plugin.restApi().get<string>(url).then(resp => {
      this.mergeMode = resp;
    });
  }

  onShowChange(change: ChangeInfo) {
    this.change = change;
    this.downstreamConfigMap = {};
    this.mergeMode = "";
    this.getMode();
    this.getDownstreamConfigMap();
  }

  onShowRevision() {
    let actionsApi = this.plugin.changeActions();

    this.action = actionsApi.getActionDetails(
      'automerge-change'
    ) as UIActionInfo;

    if (this.action) {
      actionsApi.addTapListener(this.action.__key, () => {
        this.onAutomergeChange();
      });
    }
  }
}
