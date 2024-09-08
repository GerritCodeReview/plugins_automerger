package com.googlesource.gerrit.plugins.automerger;

/** Class to hold input for a downstream change update from a single source change. */
public class UpdateDownstreamChangeInput {
  public String upstreamRevision;
  public String upstreamSubject;
  public int downstreamChangeNumber;
  public int upstreamChangeNumber;
  public int patchSetNumber;
  public String downstreamBranch;
  public String topic;
  public boolean doChange;
}
