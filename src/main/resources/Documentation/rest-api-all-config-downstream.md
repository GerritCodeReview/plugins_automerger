automerger config-downstream
=============================

NAME
----
all-config-downstream - Get set of all downstream branches

SYNOPSIS
--------
>     GET /projects/{project-name}/branches/{branch-id}/automerger~all-config-downstream

DESCRIPTION
-----------
Returns a list of branch names that are downstream, including ones more than one
hop away.

REQUEST
-----------
```
  GET /projects/{project-name}/branches/{branch-id}/automerger~all-config-downstream HTTP/1.0
```

RESPONSE
-----------
```
  HTTP/1.1 200 OK
  Content-Disposition: attachment
  Content-Type: application/json;charset=UTF-8
  )]}'
  [
    "master", "branch_two"
  ]
```
