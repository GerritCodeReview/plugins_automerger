package com.googlesource.gerrit.plugins.automerger;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * ChangeCreatorProvider provides the appropriate ChangeCreatorApi implementation based on the
 * plugin's configuration.
 */
public class ChangeCreatorProvider implements Provider<ChangeCreatorApi> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final GerritApi gApi;
  private final ConfigLoader config;

  @Inject
  public ChangeCreatorProvider(
      GerritApi gApi,
      ConfigLoader config
  ) {
    this.gApi = gApi;
    this.config = config;
  }

  @Override
  public ChangeCreatorApi get() {
    ChangeMode changeMode = ChangeMode.MERGE;
    try {
      changeMode = config.changeMode();
    } catch (ConfigInvalidException e) {
      logger.atWarning().log(
          "Unable to read the config for cherryPickMode value. Defaulting to legacy merge-mode behavior");
    }

    if(changeMode == ChangeMode.CHERRY_PICK){
      return new CherryPickChangeCreator(gApi);
    } else {
      return new MergeChangeCreator(gApi);
    }
  }
}
