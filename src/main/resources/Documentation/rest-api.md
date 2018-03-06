Automerger - REST API
============================

This page describes the REST endpoints that are added by the automerger plugin.

Please also take note of the general information on the
[REST API](../../../Documentation/rest-api.html).

<a id="automerger-endpoints"> Automerger Endpoints
-------------------------------------------------

### <a id="config-downstream"> Config Downstream
POST /projects/[\{project-name\}](../../../Documentation/rest-api-projects.html#project-name)/automerger~config-downstream

Returns a map of branches that are one hop downstream to whether or not it
should be skipped by default.

#### Request

```
  POST /projects/platform/test_data/automerger~config-downstream HTTP/1.0
  Content-Type application/json;charset=UTF-8

  {
    "subject": "DO NOT MERGE i am a test subject"
  }
```

#### Response

```
  HTTP/1.1 200 OK
  Content-Disposition: attachment
  Content-Type: application/json;charset=UTF-8
  )]}'
  {
    "master": true,
    "branch_two": false
  }
```

### <a id="all-config-downstream"> All Config Downstream
GET /projects/[\{project-name\}](../../../Documentation/rest-api-projects.html#project-name)/branches/[\{branch-id\}](../../../Documentation/rest-api-projects.html#branch-id)/automerger~all-config-downstream

Returns a list of branch names that are downstream, including ones more than one
hop away.

#### Request

```
  GET /projects/platform/test_data/branches/test_branch_name/automerger~all-config-downstream HTTP/1.0
```

#### Response

```
  HTTP/1.1 200 OK
  Content-Disposition: attachment
  Content-Type: application/json;charset=UTF-8
  )]}'
  [
    "master", "branch_two"
  ]
```

### <a id="automerge-change"> Automerge Change
POST /changes/[\{change-id\}](../../../Documentation/rest-api-changes.html#change-id)/revisions/[\{revision-id\}](../../../Documentation/rest-api-changes.html#revision-id)/automerger~automerge-change

Automerges changes based on the given map, with merges being done with the
strategy `-s ours` if the value in the map is false.

#### Request

```
  POST /changes/Id3adb33f/revisions/1/automerger~automerge-change HTTP/1.0
  Content-Type application/json;charset=UTF-8

  {
    "master": true,
    "branch_two": false
  }
```

#### Response

```
  HTTP/1.1 204 No Content
```