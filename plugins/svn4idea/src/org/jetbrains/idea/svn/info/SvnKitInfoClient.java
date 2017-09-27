/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.info;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.api.BaseSvnClient;
import org.jetbrains.idea.svn.api.Target;
import org.jetbrains.idea.svn.commandLine.SvnBindException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNWCClient;

import java.io.File;
import java.util.Collection;

public class SvnKitInfoClient extends BaseSvnClient implements InfoClient {

  public SVNWCClient getClient() {
    return myVcs.getSvnKitManager().createWCClient();
  }

  @Override
  public Info doInfo(@NotNull File path, @Nullable SVNRevision revision) throws SvnBindException {
    try {
      return Info.create(getClient().doInfo(path, revision));
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @Override
  public Info doInfo(@NotNull Target target, @Nullable SVNRevision revision) throws SvnBindException {
    assertUrl(target);

    try {
      return Info.create(getClient().doInfo(target.getUrl(), target.getPegRevision(), revision));
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @Override
  public void doInfo(@NotNull Collection<File> paths, @Nullable InfoConsumer handler) {
    throw new UnsupportedOperationException();
  }
}
