package com.googlesource.gerrit.plugins.automerger;

import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.config.ConfigResource;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * AutomergeMode will return the configured mode to create downstream changes.
 */
class AutomergeMode implements RestReadView<ConfigResource> {

  protected ConfigLoader config;

  /**
   * Initializer for this class that sets the config.
   *
   * @param config Config for this plugin.
   */
  @Inject
  public AutomergeMode(ConfigLoader config) {
    this.config = config;
  }

  /**
   * Return the Automerger mode: either MERGE or CHERRY-PICK.
   *
   * @return Either MERGE or CHERRY-PICK.
   * @throws RestApiException
   * @throws IOException
   */
  @Override
  public Response<String> apply(ConfigResource configResource)
      throws RestApiException, IOException {

    try {
      if(config.changeMode() == ChangeMode.CHERRY_PICK){
        return Response.ok("CHERRY-PICK");
      } else {
        return Response.ok("MERGE");
      }
    } catch (ConfigInvalidException e) {
      throw new ResourceConflictException(
          "Automerger configuration file is invalid: " + e.getMessage());
    }
  }
}
