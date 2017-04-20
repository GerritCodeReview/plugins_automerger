@PLUGIN@ Configuration
======================

The configuration of the @PLUGIN@ plugin is done on the project level in
the `@PLUGIN@.config` file of the project.

```
  [global]
    automergeLabel = Code-Review
    hostName = https://hostname.example.com
    conflictMessage = Merge conflict found on ${branch}\n\
" # Example of multiline conflict message"
    manifestProject = platform/manifest
    manifestFile = default.xml
    alwaysBlankMerge = .*SKIP ME ALWAYS.*
    blankMerge = .*RESTRICT AUTOMERGE.*
    blankMerge = .*SKIP UNLESS MERGEALL SET.*

  [@PLUGIN@ "branch1:branch2"]
    setProjects = some/project
    addProjects = some/other/project
    ignoreProjects = some/ignored/project
    mergeAll = True
    ignoreSourceManifest = False
```

global.automergeLabel
: Label to vote minAutomergeVote on when there is a merge conflict.

  When the automerger detects a merge conflict from one branch to another, it
  will vote minAutomergeVote on this label.

global.maxAutomergeVote
: Value to vote on a successful automerge.

  When the automerger succeeds in merging downstream, it will vote
  maxAutomergeVote on the downstream change. The original change uploaded by
  the user will have a vote of 0, so that it can be easily programatically
  distinguished from the otheres.

global.minAutomergeVote
: Value to vote on a failed automerge.

  When the automerger detects a merge conflict from one branch to another, it
  will vote minAutomergeVote for the configured automergeLabel.

global.hostName
: Hostname to use in a custom conflict message.

  The automerger will attempt to use the canonicalWebUrl to fill in the
  hostname. However, if it is unable to do so for whatever reason, it can be
  overriden using this field.

global.conflictMessage
: Message to comment with the automergeLabel vote if there is a merge conflict.

  When the automerger detects a merge conflict from one branch to another, it
  will vote -1 on the automergeLabel. The message on the vote can be custom
  configured to include:

  - branch
  - revision
  - hostname
  - topic
  - conflict

  For example, you could configure the @PLUGIN@.config to include:

  ```
    conflictMessage = Conflict message ${conflict} found on branch ${branch}
  ```

global.manifestProject
: Project to look for a [repo manifest][1] in.

  The automerger will attempt to look for the manifest project in this project.

global.manifestFile
: File to look for a [repo manifest][1] in.

  The automerger will attempt to look for a repo manifest in this file.

[1]: https://gerrit.googlesource.com/git-repo/

global.blankMerge
: Pattern for skipping changes.

  If the automerger matches this regex in the subject of the change, it will
  merge with "-s ours" to all downstream branches, effectively skipping the
  change.

  If mergeAll is set to True for a branch, however, it will merge downstream
  normally for that branch.

global.alwaysBlankMerge
: Pattern for always skipping changes.

  If the automerger matches this regex in the subject of the change, it will
  merge with "-s ours" to all downstream branches, effectively skipping the
  change.

  Even if mergeAll is set to True for a branch, it will still merge with
  "-s ours".

@PLUGIN@.branch1:branch2.setProjects
: Projects to automerge for.

  If setProjects is set, it overrides the default scope.

@PLUGIN@.branch1:branch2.addProjects
: Projects to add on top of the projects already in scope.

  If setProjects is set, this will add projects on top of the setProjects.
  If setProjects is not set, this will add projects on top of the default scope.

@PLUGIN@.branch1:branch2.ignoreProjects
: Projects to ignore on top of the projects already in scope

  If setProjects is set, this will ignore projects on top of the setProjects.
  If setProjects is not set, this will ignore projects on top of the default
  scope.

@PLUGIN@.branch1:branch2.mergeAll
: If this is true, the blankMerge regex will be ignored.

  When mergeAll is true, the blankMerge regex will be ignored but the
  alwaysBlankMerge regex will still be honored.

@PLUGIN@.branch1:branch2.ignoreSourceManifest
: If this is true, the default scope will be modified.

  The default scope normally includes the intersection of all projects in the
  manifest of branch1 whose revisions are branch1, and all projects in the
  manifest of branch2 whose revisions are branch2.

  If ignoreSourceManifest is true, the scope will become all projects in the
  manifest of branch2 whose revisions are branch2.