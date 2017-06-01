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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static com.google.common.truth.Truth.assertThat;

public class QueryBuilderTest {
  @Rule public ExpectedException exception = ExpectedException.none();
  private QueryBuilder queryBuilder;

  @Before
  public void setUp() throws Exception {
    queryBuilder = new QueryBuilder();
  }

  @Test
  public void basicParseTest() throws Exception {
    queryBuilder.addParameter("status", "open");
    assertThat(queryBuilder.get()).isEqualTo("status:\"open\"");
  }

  @Test
  public void nullTest() throws Exception {
    queryBuilder.addParameter("status", "open");
    exception.expect(InvalidQueryParameterException.class);
    exception.expectMessage("Cannot use null value for key or value of query.");
    queryBuilder.addParameter("topic", null);
  }

  @Test
  public void removeParameterTest() throws Exception {
    queryBuilder.addParameter("status", "open");
    queryBuilder.addParameter("branch", "master");
    queryBuilder.removeParameter("status");
    assertThat(queryBuilder.get()).isEqualTo("branch:\"master\"");
  }

  @Test
  public void escapeQuoteTest() throws Exception {
    queryBuilder.addParameter("topic", "topic\"with\"quotes");
    assertThat(queryBuilder.get()).isEqualTo("topic:{topic\"with\"quotes}");
  }

  @Test
  public void escapeBraceTest() throws Exception {
    queryBuilder.addParameter("topic", "topic{with}braces");
    assertThat(queryBuilder.get()).isEqualTo("topic:\"topic{with}braces\"");
  }

  @Test
  public void errorOnQuotesAndBracesTest() throws Exception {
    exception.expect(InvalidQueryParameterException.class);
    exception.expectMessage("Gerrit does not support both quotes and braces in a query.");
    queryBuilder.addParameter("topic", "topic{\"with\"}both");
  }
}
