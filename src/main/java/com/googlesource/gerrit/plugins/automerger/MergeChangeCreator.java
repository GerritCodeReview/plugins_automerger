package com.googlesource.gerrit.plugins.automerger;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.RestoreInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.MergePatchSetInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * MergeChangeCreator handles creation and updating of downstream changes as merges.
 */
public class MergeChangeCreator implements ChangeCreatorApi {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String SUBJECT_PREFIX = "automerger";
  private final GerritApi gApi;
  @Inject
  public MergeChangeCreator(
      GerritApi gApi) {
    this.gApi = gApi;
  }
  /**
   * Create a single downstream merge.
   *
   * @param sdsChangeInput Input containing metadata for the merge.
   * @param currentTopic Current topic to create change in.
   * @throws RestApiException
   * @throws ConfigInvalidException
   * @throws InvalidQueryParameterException
   * @throws StorageException
   */
  @Override
  public ChangeApi create(SingleDownstreamChangeInput sdsChangeInput,
      String currentTopic)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException, StorageException {

    if (isAlreadyMerged(sdsChangeInput, currentTopic)) {
      logger.atInfo().log(
          "Commit %s already merged into %s, not automerging again.",
          sdsChangeInput.currentRevision, sdsChangeInput.downstreamBranch);
      return null;
    }

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = sdsChangeInput.currentRevision;
    mergeInput.strategy = "recursive";

    logger.atFine().log("Creating downstream merge for %s", sdsChangeInput.currentRevision);
    ChangeInput downstreamChangeInput = new ChangeInput();
    downstreamChangeInput.project = sdsChangeInput.project;
    downstreamChangeInput.branch = sdsChangeInput.downstreamBranch;
    downstreamChangeInput.subject =
        ChangeUtils.getSubjectForDownstreamChange(SUBJECT_PREFIX, sdsChangeInput.subject, sdsChangeInput.currentRevision, !sdsChangeInput.doChange);
    downstreamChangeInput.topic = currentTopic;
    downstreamChangeInput.merge = mergeInput;
    downstreamChangeInput.notify = NotifyHandling.NONE;

    downstreamChangeInput.baseChange =
        ChangeUtils.getBaseChangeIdForMerge(gApi,
            ChangeUtils.getChangeParents(gApi, sdsChangeInput.changeNumber, sdsChangeInput.currentRevision),
            sdsChangeInput.downstreamBranch);

    if (!sdsChangeInput.doChange) {
      mergeInput.strategy = "ours";
      logger.atFine().log(
          "Skipping merge for %s to %s",
          sdsChangeInput.currentRevision, sdsChangeInput.downstreamBranch);
    }

    return gApi.changes().create(downstreamChangeInput);
  }

  @Override
  public void update(UpdateDownstreamChangeInput updateDownstreamChangeInput)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException {

    MergeInput mergeInput = new MergeInput();
    mergeInput.source = updateDownstreamChangeInput.upstreamRevision;

    MergePatchSetInput mergePatchSetInput = new MergePatchSetInput();

    mergePatchSetInput.subject =
        ChangeUtils.getSubjectForDownstreamChange(SUBJECT_PREFIX,
            updateDownstreamChangeInput.upstreamSubject,
            updateDownstreamChangeInput.upstreamRevision, !updateDownstreamChangeInput.doChange);
    if (!updateDownstreamChangeInput.doChange) {
      mergeInput.strategy = "ours";
      logger.atFine().log("Skipping merge for %s on %s",
          updateDownstreamChangeInput.upstreamRevision, updateDownstreamChangeInput.downstreamChangeNumber);
    }
    mergePatchSetInput.merge = mergeInput;

    mergePatchSetInput.baseChange =
        ChangeUtils.getBaseChangeIdForMerge(gApi,
            ChangeUtils.getChangeParents(gApi, updateDownstreamChangeInput.upstreamChangeNumber,
                updateDownstreamChangeInput.upstreamRevision), updateDownstreamChangeInput.downstreamBranch);

    ChangeApi originalChange = gApi.changes().id(updateDownstreamChangeInput.downstreamChangeNumber);

    if (originalChange.info().status == ChangeStatus.ABANDONED) {
      RestoreInput restoreInput = new RestoreInput();
      restoreInput.message = "Restoring change due to upstream automerge.";
      originalChange.restore(restoreInput);
    }

    originalChange.createMergePatchSet(mergePatchSetInput);
  }

  boolean isAlreadyMerged(SingleDownstreamChangeInput sdsChangeInput, String currentTopic)
      throws InvalidQueryParameterException, RestApiException {
    // If we've already merged this commit to this branch, don't do it again.
    List<ChangeInfo> changes =
        ChangeUtils.getChangesInTopicAndBranch(gApi, currentTopic, sdsChangeInput.downstreamBranch);
    for (ChangeInfo change : changes) {
      List<CommitInfo> parents = change.revisions.get(change.currentRevision).commit.parents;
      if (parents.size() > 1) {
        String secondParent = parents.get(1).commit;
        if (secondParent.equals(sdsChangeInput.currentRevision)) {
          return true;
        }
      }
    }
    return false;
  }

}
