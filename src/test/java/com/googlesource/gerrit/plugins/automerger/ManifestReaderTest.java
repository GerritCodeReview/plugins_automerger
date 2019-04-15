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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class ManifestReaderTest {
  private ManifestReader manifestReader;
  private String manifestString;

  @Before
  public void setUp() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("default.xml")) {
      manifestString = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8));
    }
    manifestReader = new ManifestReader("master", manifestString);
  }

  @Test
  public void basicParseTest() throws Exception {
    Set<String> expectedSet = new HashSet<>();
    expectedSet.add("platform/whee");
    expectedSet.add("whoo");
    assertThat(manifestReader.getProjects()).isEqualTo(expectedSet);
  }

  @Test
  public void branchDifferentFromDefaultRevisionTest() throws Exception {
    ManifestReader aospManifestReader = new ManifestReader("mirror-aosp-master", manifestString);
    Set<String> expectedSet = new HashSet<>();
    expectedSet.add("platform/whaa");
    assertThat(aospManifestReader.getProjects()).isEqualTo(expectedSet);
  }
}
