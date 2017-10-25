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

package com.googlesource.gerrit.plugins.automerger.helpers;

public class ConfigOption {
  public String section;
  public String subsection;
  public String key;
  public String value;

  /**
   * @param section
   * @param subsection
   * @param key
   * @param value
   */
  public ConfigOption(String section, String subsection, String key, String value) {
    this.section = section;
    this.subsection = subsection;
    this.key = key;
    this.value = value;
  }
}
