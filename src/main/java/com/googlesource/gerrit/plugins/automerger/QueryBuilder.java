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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Class to construct a query with escaped arguments. */
public class QueryBuilder {
  private Map<String, String> queryStringMap;

  public QueryBuilder() {
    this.queryStringMap = new HashMap<String, String>();
  }

  public void addParameter(String key, String value) throws InvalidQueryParameterException {
    if (value.contains("\"") && (value.contains("{") || value.contains("}"))) {
      // Gerrit does not support search string escaping as of 5/16/2017
      // see https://bugs.chromium.org/p/gerrit/issues/detail?id=5617
      throw new InvalidQueryParameterException(
          "Gerrit does not support both quotes and braces in a query.");
    } else if (value.contains("\"")) {
      queryStringMap.put(key, "{" + value + "}");
    } else {
      queryStringMap.put(key, "\"" + value + "\"");
    }
  }

  public String removeParameter(String key) {
    return queryStringMap.remove(key);
  }

  public String get() {
    List<String> queryStringList = new ArrayList<String>();
    for (Map.Entry<String, String> entry : queryStringMap.entrySet()) {
      queryStringList.add(entry.getKey() + ":" + entry.getValue());
    }
    return String.join(" ", queryStringList);
  }
}
