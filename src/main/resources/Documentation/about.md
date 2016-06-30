Plugin to allow automatically merging changes from one branch to another based
on a config file.

When the plugin detects a patchset has been created, it will
merge downstream until it hits a merge conflict. On the
conflicting merge, it will vote -1 on a configurable label
and provide instructions to resolving the merge conflict.

Draft changes will be ignored until published.

The plugin will put all the auto-created changes in the
same topic as the original change (or create a topic if
none exists). If a user updates the topic, it will update
the topic of all the downstream merges.

If there are existing downstream merges from a previous
automerged patchset, it will abandon them all without
email spamming the end user.