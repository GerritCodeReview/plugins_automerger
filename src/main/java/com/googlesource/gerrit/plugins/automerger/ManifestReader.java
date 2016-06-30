package com.googlesource.gerrit.plugins.automerger;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public class ManifestReader {
  private static final Logger log = LoggerFactory.getLogger(ManifestReader.class);

  String manifestString;
  String branch;

  public ManifestReader(String branch, String manifestString) {
    this.manifestString = manifestString;
    this.branch = branch;
  }

  public Set<String> getProjects() {
    Set<String> projectSet = new HashSet<String>();

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
          String path = projectElement.getAttribute("path");
          String name = projectElement.getAttribute("name");

          String revision = projectElement.getAttribute("revision");
          if ("".equals(revision)) {
            revision = defaultRevision;
          }

          // Only add to list of projects in scope if revision is same as manifest branch
          if (revision.equals(branch)) {
            projectSet.add(name);
          }
        }
      }

    } catch (SAXException e) {
      log.error("SAXException", e);
    } catch (ParserConfigurationException e) {
      log.error("ParserConfigurationException", e);
    } catch (IOException e) {
      log.error("Failed to read config.", e);
    }
    return projectSet;
  }
}
