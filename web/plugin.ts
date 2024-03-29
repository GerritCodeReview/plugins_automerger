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
import '@gerritcodereview/typescript-api/gerrit';
import {EventType} from '@gerritcodereview/typescript-api/plugin';
import {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';
import {Automerger} from './automerger';

window.Gerrit.install(plugin => {
  const automerger = new Automerger(plugin);
  plugin.on(EventType.SHOW_CHANGE, (change: ChangeInfo) =>
    automerger.onShowChange(change)
  );
  plugin.on(EventType.SHOW_REVISION_ACTIONS, () =>
    automerger.onShowRevision()
  );
});
