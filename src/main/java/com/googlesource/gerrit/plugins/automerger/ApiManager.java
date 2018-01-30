// Copyright (C) 2018 The Android Open Source Project
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

import com.google.gerrit.extensions.api.GerritApi;
import com.google.inject.Inject;

/** Interface for sending REST API requests to another Gerrit host. */
public interface ApiManager {
  public GerritApi forHostname(String hostname);
  
  class DefaultApiManager implements ApiManager {
    protected GerritApi gApi;
    
    @Inject
    public DefaultApiManager(GerritApi gApi) {
      this.gApi = gApi;
    }

    @Override
    public GerritApi forHostname(String hostname) {
      return gApi;
    }
    
  }
}
