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

import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Class to read a repo manifest. */
public class ManifestReader {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String manifestString;
  private final String branch;

  public ManifestReader(String branch, String manifestString) {
    this.manifestString = manifestString;
    this.branch = branch;
  }

  /**
   * Read the given repo manifest, then parse and return the set of projects in it.
   *
   * @return The set of projects in the manifest.
   */
  public Set<String> getProjects() {
    Set<String> projectSet = new HashSet<>();

    try {
      DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
      Document document = documentBuilder.parse(new InputSource(new StringReader(manifestString)));

      Element defaultElement = (Element) document.getElementsByTagName("default").item(0);
      String defaultRevision = defaultElement.getAttribute("revision");

      NodeList projectNodes = document.getElementsByTagName("project");
      for (int i = 0; i < projectNodes.getLength(); i++) {
        Node projectNode = projectNodes.item(i);
        if (projectNode.getNodeType() == Node.ELEMENT_NODE) {
          Element projectElement = (Element) projectNode;
          String name = projectElement.getAttribute("name");

          String revision = projectElement.getAttribute("revision");
          if ("".equals(revision)) {
            revision = defaultRevision;
          }

          // Only add to list of projects in scope if revision is same as
          // manifest branch
          if (revision.equals(branch)) {
            projectSet.add(name);
          }
        }
      }

    } catch (SAXException | ParserConfigurationException | IOException e) {
      logger.atSevere().withCause(e).log("Exception on manifest for branch %s", branch);
    }
    return projectSet;
  }
}
