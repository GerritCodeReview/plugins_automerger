package com.googlesource.gerrit.plugins.automerger;

import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.restapi.RestApiException;
import org.eclipse.jgit.errors.ConfigInvalidException;

/** ChangeCreatorApi is the interface used to create or update downstream changes. */
public interface ChangeCreatorApi {
  ChangeApi create(SingleDownstreamChangeInput sdsChangeInput, String currentTopic)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException,
      StorageException;

  void update(UpdateDownstreamChangeInput updateDownstreamChangeInput)
      throws RestApiException, ConfigInvalidException, InvalidQueryParameterException;
}
