@PLUGIN@ config-downstream
=============================

NAME
----
config-downstream - Get the downstream config map

SYNOPSIS
--------
>     POST /projects/{project-name}/automerger~config-downstream

DESCRIPTION
-----------
Returns a map of branches that are one hop downstream to whether or not it
should be skipped by default.

OPTIONS
-------

--subject
> The subject of the current change
