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
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.gerrit.acceptance.PluginDaemonTest;

import org.junit.Before;
import org.junit.Test;

import org.easymock.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class ManifestReaderIT extends PluginDaemonTest {
  ManifestReader manifestReader;
  File manifestFile;

  @Before
  public void setUp() throws Exception {
    String manifestPath = Paths.get(".", "plugins", "automerger", "src", "test",
        "resources", "com", "googlesource", "gerrit", "plugins", "automerger",
        "default.xml").toAbsolutePath().normalize().toString();
    manifestFile = new File(manifestPath);
    manifestReader = new ManifestReader("master", Files.toString(manifestFile,
        Charsets.UTF_8));
  }

  @Test
  public void basicParseTest() throws Exception {
    Set<String> expectedSet = new HashSet<String>();
    expectedSet.add("platform/whee");
    expectedSet.add("whoo");
    assertThat(manifestReader.getProjects()).isEqualTo(expectedSet);
  }

  @Test
  public void branchDifferentFromDefaultRevisionTest() throws Exception {
    ManifestReader aospManifestReader = new ManifestReader("mirror-aosp-master",
        Files.toString(manifestFile, Charsets.UTF_8));
    Set<String> expectedSet = new HashSet<String>();
    expectedSet.add("platform/whaa");
    assertThat(aospManifestReader.getProjects()).isEqualTo(expectedSet);
  }
}
