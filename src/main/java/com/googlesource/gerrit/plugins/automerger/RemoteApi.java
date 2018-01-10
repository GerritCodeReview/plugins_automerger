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

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.gerrit.server.OutputFormat;
import com.google.gson.JsonObject;
import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;

/** Class to set up an HTTP session. */
public class RemoteApi {
  private HttpHost host;
  private final CloseableHttpClient client;
  private final HttpContext context;

  public RemoteApi(String url, HttpContext context) {
    host = new HttpHost(url);
    this.client = HttpClients.createMinimal();
    this.context = context;
  }

  public HttpResponse createMergeChange(String project, String branch, String source)
      throws ClientProtocolException, IOException {
    String endPoint = Joiner.on("/").join(host.getHostName(), "a/changes/");
    JsonObject bodyJson = new JsonObject();
    bodyJson.addProperty("project", project);
    bodyJson.addProperty("branch", branch);
    bodyJson.addProperty("subject", "some subject here");
    bodyJson.addProperty("notify", "NONE");

    JsonObject mergeJson = new JsonObject();
    mergeJson.addProperty("source", source);
    bodyJson.add("merge", mergeJson);
    return post(endPoint, bodyJson);
  }

  private HttpResponse post(String endPoint, Object content)
      throws ClientProtocolException, IOException {
    HttpPost post = new HttpPost(endPoint);
    if (content != null) {
      post.addHeader(new BasicHeader("Content-Type", "application/json"));
      post.setEntity(
          new StringEntity(
              OutputFormat.JSON_COMPACT.newGson().toJson(content), Charsets.UTF_8.name()));
    }
    return execute(post);
  }

  private HttpResponse execute(HttpRequest request) throws ClientProtocolException, IOException {
    return client.execute(host, request, context);
  }
}
