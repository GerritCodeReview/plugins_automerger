package com.googlesource.gerrit.plugins.automerger;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.HashtagsInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * CherryPickChangeCreator handles creation and updating of downstream changes as cherry-picks.
 */
public class CherryPickChangeCreator implements ChangeCreatorApi {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String SUBJECT_PREFIX = "autocherry";
  private final GerritApi gApi;
  @Inject
  public CherryPickChangeCreator(
      GerritApi gApi) {
    this.gApi = gApi;
  }

  /**
   * Create a single downstream cherry-pick.
   *
   * <p>On a skip, only a hashtag is applied to the upstream change.</p>
   *
   * @param sdsChangeInput Input containing metadata for the cherry-pick.
   * @param currentTopic Current topic to create change in.
   * @throws RestApiException
   * @throws ConfigInvalidException
   * @throws InvalidQueryParameterException
   * @throws StorageException
   */
  @Override
  public ChangeApi create(SingleDownstreamChangeInput sdsChangeInput, String currentTopic)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException, StorageException {

    if (!sdsChangeInput.doChange) {
      logger.atFine().log(
          "Skipping cherry-pick for %s to %s",
          sdsChangeInput.currentRevision, sdsChangeInput.downstreamBranch);

      applySkipHashtag(sdsChangeInput);

      return null;
    }

    // This mirrors the MergeChangeCreator, although I don't believe it is possible with
    // cherry-picks. Merge mode can encounter this scenario in diamond merges.
    if (isAlreadyCherryPicked(sdsChangeInput, currentTopic)) {
      logger.atInfo().log(
          "Commit %s already cherry-picked into %s, not cherry-picking again.",
          sdsChangeInput.currentRevision, sdsChangeInput.downstreamBranch);
      return null;
    }

    removeSkipHashtag(sdsChangeInput);

    CherryPickInput cherryPickInput = new CherryPickInput();
    cherryPickInput.base =
        ChangeUtils.getBaseChangeRevisionForCherryPick(gApi,
            ChangeUtils.getChangeParents(gApi, sdsChangeInput.changeNumber, sdsChangeInput.currentRevision),
            sdsChangeInput.downstreamBranch);
    cherryPickInput.message =
        ChangeUtils.getSubjectForDownstreamChange(SUBJECT_PREFIX, sdsChangeInput.subject, sdsChangeInput.currentRevision, !sdsChangeInput.doChange);
    cherryPickInput.destination = sdsChangeInput.downstreamBranch;
    cherryPickInput.notify = NotifyHandling.NONE;
    cherryPickInput.topic = currentTopic;

    return gApi.changes().id(sdsChangeInput.changeNumber).current().cherryPick(cherryPickInput);
  }

  private Set<String> getSkipHashtagSet(String downstreamBranch) {
    Set<String> set = new HashSet<>();
    set.add(ChangeUtils.getSkipHashtag(downstreamBranch));
    return set;
  }

  private void applySkipHashtag(SingleDownstreamChangeInput sdsChangeInput) throws RestApiException {
    ChangeApi originalChange = gApi.changes().id(sdsChangeInput.changeNumber);
    Set<String> set = getSkipHashtagSet(sdsChangeInput.downstreamBranch);
    originalChange.setHashtags(new HashtagsInput(set));
  }

  private void removeSkipHashtag(SingleDownstreamChangeInput sdsChangeInput) throws RestApiException {
    ChangeApi originalChange = gApi.changes().id(sdsChangeInput.changeNumber);
    Set<String> set = getSkipHashtagSet(sdsChangeInput.downstreamBranch);

    // It is safe to blindly attempt to remove the hashtag.
    originalChange.setHashtags(new HashtagsInput(null, set));
  }

  @Override
  public void update(UpdateDownstreamChangeInput updateDownstreamChangeInput)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException {

    // For cherry-picks, we don't update the prior existing commit with a patch application.
    // Doing this will not update the 'Cherry pick of' metadata with the correct patchset.
    // Instead, we abandon the old one and create a new one.
    AbandonInput abandonInput = new AbandonInput();
    abandonInput.notify = NotifyHandling.NONE;
    if(!updateDownstreamChangeInput.doChange){
      abandonInput.message = "The cherry-pick from upstream is now skipped";
    } else {
      abandonInput.message = "The upstream patch set is no longer current";
    }
    gApi.changes().id(updateDownstreamChangeInput.downstreamChangeNumber).abandon(abandonInput);

    SingleDownstreamChangeInput sdsChangeInput = getSingleDownstreamChangeInput(
        updateDownstreamChangeInput);

    // We still "create" in the event of a skip to apply the appropriate hashtag.
    ChangeApi newDownstream = create(sdsChangeInput, updateDownstreamChangeInput.topic);
    if(newDownstream != null) {
      ChangeUtils.tagChange(gApi, newDownstream.get(), "Automerger change created!");
    }
  }

  private static SingleDownstreamChangeInput getSingleDownstreamChangeInput(
      UpdateDownstreamChangeInput updateDownstreamChangeInput) {

    SingleDownstreamChangeInput sdsChangeInput = new SingleDownstreamChangeInput();
    sdsChangeInput.subject =
        ChangeUtils.getSubjectForDownstreamChange(SUBJECT_PREFIX,
            updateDownstreamChangeInput.upstreamSubject,
            updateDownstreamChangeInput.upstreamRevision, !updateDownstreamChangeInput.doChange);
    sdsChangeInput.changeNumber = updateDownstreamChangeInput.upstreamChangeNumber;
    sdsChangeInput.patchsetNumber = updateDownstreamChangeInput.patchSetNumber;
    sdsChangeInput.doChange = updateDownstreamChangeInput.doChange;
    sdsChangeInput.currentRevision = updateDownstreamChangeInput.upstreamRevision;
    sdsChangeInput.downstreamBranch = updateDownstreamChangeInput.downstreamBranch;
    return sdsChangeInput;
  }

  boolean isAlreadyCherryPicked(SingleDownstreamChangeInput sdsChangeInput, String currentTopic)
      throws InvalidQueryParameterException, RestApiException {

    List<ChangeInfo> changes =
        ChangeUtils.getChangesInTopicAndBranch(gApi, currentTopic, sdsChangeInput.downstreamBranch);
    for (ChangeInfo change : changes) {
      if(change.cherryPickOfChange != null && change.cherryPickOfChange.equals(sdsChangeInput.changeNumber)
          && change.cherryPickOfPatchSet != null && change.cherryPickOfPatchSet.equals(sdsChangeInput.patchsetNumber)) {
        return true;
      }
    }
    return false;
  }
}
