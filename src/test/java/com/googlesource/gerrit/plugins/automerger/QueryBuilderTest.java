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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class QueryBuilderTest {
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
    InvalidQueryParameterException thrown =
        assertThrows(
            InvalidQueryParameterException.class, () -> queryBuilder.addParameter("topic", null));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot use null value for key or value of query.");
  }

  @Test
  public void emptyStringTest() throws Exception {
    queryBuilder.addParameter("topic", "");
    assertThat(queryBuilder.get()).isEqualTo("topic:\"\"");
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
    InvalidQueryParameterException thrown =
        assertThrows(
            InvalidQueryParameterException.class,
            () -> queryBuilder.addParameter("topic", "topic{\"with\"}both"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Gerrit does not support both quotes and braces in a query.");
  }
}
