/**
 * @license
 * Copyright (C) 2021 The Android Open Source Project
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
import './test/test-setup';
import {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import {
  ChangeInfo,
  ChangeStatus,
  HttpMethod,
  BranchName,
  NumericChangeId,
  RepoName,
  ChangeId,
  Timestamp,
  ChangeInfoId,
  PatchSetNumber,
} from '@gerritcodereview/typescript-api/rest-api';
import {Automerger, ConfigMap, UIActionInfo} from './automerger';
import {queryAll, queryAndAssert, waitUntil} from './test/test-util';
import {assert} from '@open-wc/testing';
import sinon from 'sinon';

const change: ChangeInfo = {
  _number: 123 as NumericChangeId,
  branch: 'test-branch' as BranchName,
  change_id: 'I123456789abcdef932b39a2a809879fb163ccb41' as ChangeId,
  created: '2021-09-01 12:12:12.000000000' as Timestamp,
  deletions: 0,
  id: 'test-repo~test-branch~I123456789abcdef932b39a2a809879fb163ccb41' as ChangeInfoId,
  insertions: 0,
  owner: {},
  project: 'test-repo' as RepoName,
  reviewers: {},
  status: ChangeStatus.NEW,
  subject: 'test-subject',
  updated: '2021-09-02 12:12:12.000000000' as Timestamp,
  current_revision_number: 1 as PatchSetNumber
};

const configMap: ConfigMap = {
  'branch-1': true,
  'branch-2': true,
  'branch-3': false,
};

suite('automerger tests', () => {
  let automerger: Automerger;
  let callback: (() => void) | undefined;
  let getStub: sinon.SinonStub;
  let postStub: sinon.SinonStub;
  let reloadStub: sinon.SinonStub;
  let popup: HTMLElement | undefined;

  const actionInfo: UIActionInfo = {
    __key: 'test-key',
    __url: 'http://test-url',
    enabled: true,
    label: 'test-label',
    method: HttpMethod.GET,
    title: 'test-title',
  };

  setup(async () => {
    getStub = sinon.stub();
    getStub.returns(Promise.resolve({}));
    postStub = sinon.stub();
    postStub.returns(Promise.resolve(configMap));
    const fakePlugin = {
      popup: () =>
        Promise.resolve({
          _getElement: () => {
            popup = document.createElement('div');
            return popup;
          },
        }),
      changeActions: () => {
        return {
          getActionDetails: () => actionInfo,
          addTapListener: (_: string, cb: () => void) => (callback = cb),
        };
      },
      restApi: () => {
        return {
          get: getStub,
          post: postStub,
        };
      },
    } as unknown as PluginApi;
    automerger = new Automerger(fakePlugin);
    reloadStub = sinon.stub(automerger, 'windowReload');
  });

  teardown(() => {
    if (popup) popup.remove();
    popup = undefined;
    callback = undefined;
  });

  test('callback set, popup created', async () => {
    assert.isNotOk(callback);
    assert.isNotOk(popup);

    automerger.onShowChange(change);
    assert.isOk(callback, 'callback expected to be set');
    if (callback) callback();
    await waitUntil(() => popup !== undefined);
    assert.equal(popup?.childElementCount, 1);
  });

  test('popup contains 3 checkboxes', async () => {
    automerger.onShowChange(change);
    await waitUntil(() => postStub.called);
    if (callback) callback();
    await waitUntil(() => popup !== undefined);
    const checkboxes = queryAll(popup!, 'input');
    assert.equal(checkboxes?.length, 3);
  });

  test('popup contains button, which triggers send', async () => {
    automerger.onShowChange(change);
    await waitUntil(() => postStub.called);
    if (callback) callback();
    await waitUntil(() => popup !== undefined);
    const button = queryAndAssert<HTMLElement>(popup, 'gr-button');
    button.click();
    assert.isTrue(getStub.called, 'expected send() to be called');
    await waitUntil(() => reloadStub.called);
  });
});
