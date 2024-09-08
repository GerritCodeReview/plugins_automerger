package com.googlesource.gerrit.plugins.automerger;

/**
 * ChangeMode defines the mode for creating downstream changes.
 */
public enum ChangeMode {
  MERGE,
  CHERRY_PICK
}
