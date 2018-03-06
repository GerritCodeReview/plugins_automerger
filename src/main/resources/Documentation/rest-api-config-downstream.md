automerger config-downstream
=============================

NAME
----
config-downstream - Get the downstream config map

SYNOPSIS
--------
>     POST /projects/link:rest-api-projects.html#project-name[\{project-name\}/automerger~config-downstream

DESCRIPTION
-----------
Returns a map of branches that are one hop downstream to whether or not it
should be skipped by default.

OPTIONS
-------

--subject
> The subject of the current change

REQUEST
-----------
```
  POST /projects/link:rest-api-projects.html#project-name[\{project-name\}/automerger~config-downstream HTTP/1.0
  Content-Type application/json;charset=UTF-8

  {
    "subject": "DO NOT MERGE i am a test subject"
  }
```

RESPONSE
-----------
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
