@PLUGIN@ automerge-change
=============================

NAME
----
automerge-change - Automerge a change downstream

SYNOPSIS
--------
>     POST /projects/{project-name}/automerger~automerge-change

DESCRIPTION
-----------
Returns an HTTP 204 if successful.

OPTIONS
-------

--branch_map
> A map of downstream branches to their merge value (false means it is skipped)