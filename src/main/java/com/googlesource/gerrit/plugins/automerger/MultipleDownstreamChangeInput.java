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

import java.util.Map;

/**
 * Class to hold input for a set of downstream changes from a single source change, with associated
 * metadata.
 */
public class MultipleDownstreamChangeInput {
  public Map<String, Boolean> dsBranchMap;
  public int changeNumber;
  public int patchsetNumber;
  public String project;
  public String topic;
  public String subject;
  public String obsoleteRevision;
  public String currentRevision;
}
