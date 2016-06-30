@PLUGIN@ automerge-change
=============================

NAME
----
automerge-change - Automerge a change downstream

SYNOPSIS
--------
>     POST /projects/{project-name}/@PLUGIN@~automerge-change

DESCRIPTION
-----------
Returns an HTTP 204 if successful.

OPTIONS
-------
--branch_map
> A map of downstream branches to their merge value (false means it is skipped)

REQUEST
-----------
```
  POST /projects/{project-name}/@PLUGIN@~automerge-change HTTP/1.0
  Content-Type application/json;charset=UTF-8

  {
    "master": true,
    "branch_two": false
  }
```

RESPONSE
-----------
```
  HTTP/1.1 204 No Content
```
