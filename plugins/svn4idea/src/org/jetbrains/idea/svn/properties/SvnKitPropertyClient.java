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
package org.jetbrains.idea.svn.properties;

import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.util.LineSeparator;
import com.intellij.util.ThrowableRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.api.BaseSvnClient;
import org.jetbrains.idea.svn.api.Depth;
import org.jetbrains.idea.svn.api.Target;
import org.jetbrains.idea.svn.commandLine.SvnBindException;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.wc.*;

import java.io.File;
import java.util.Map;

public class SvnKitPropertyClient extends BaseSvnClient implements PropertyClient {

  public static final ISVNOptions LF_SEPARATOR_OPTIONS = new DefaultSVNOptions() {
    @Override
    public byte[] getNativeEOL() {
      return CharsetToolkit.getUtf8Bytes(LineSeparator.LF.getSeparatorString());
    }
  };

  @Nullable
  @Override
  public PropertyValue getProperty(@NotNull Target target,
                                   @NotNull String property,
                                   boolean revisionProperty,
                                   @Nullable SVNRevision revision) throws SvnBindException {
    PropertyData resultData;

    try {
      if (!revisionProperty) {
        if (target.isFile()) {
          resultData = PropertyData.create(createClient().doGetProperty(target.getFile(), property, target.getPegRevision(), revision));
        } else {
          resultData = PropertyData.create(createClient().doGetProperty(target.getUrl(), property, target.getPegRevision(), revision));
        }
      } else {
        resultData = getRevisionProperty(target, property, revision);
      }
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }

    return resultData != null ? resultData.getValue() : null;
  }

  @NotNull
  private SVNWCClient createClient() {
    SVNWCClient client = myVcs.getSvnKitManager().createWCClient();
    client.setOptions(LF_SEPARATOR_OPTIONS);

    return client;
  }

  @Override
  public void getProperty(@NotNull Target target,
                          @NotNull String property,
                          @Nullable SVNRevision revision,
                          @Nullable Depth depth,
                          @Nullable PropertyConsumer handler) throws SvnBindException {
    runGetProperty(target, property, revision, depth, handler);
  }

  @Override
  public void list(@NotNull Target target, @Nullable SVNRevision revision, @Nullable Depth depth, @Nullable PropertyConsumer handler)
    throws SvnBindException {
    runGetProperty(target, null, revision, depth, handler);
  }

  @Override
  public void setProperty(@NotNull File file, @NotNull String property, @Nullable PropertyValue value, @Nullable Depth depth, boolean force)
    throws SvnBindException {
    try {
      createClient().doSetProperty(file, property, toPropertyValue(value), force, toDepth(depth), null, null);
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @Override
  public void setProperties(@NotNull File file, @NotNull PropertiesMap properties) throws SvnBindException {
    final SVNProperties propertiesToSet = toSvnProperties(properties);
    try {
      createClient().doSetProperty(file, (path, properties1) -> propertiesToSet, true, SVNDepth.EMPTY, null, null);
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @Override
  public void setRevisionProperty(@NotNull Target target,
                                  @NotNull String property,
                                  @NotNull SVNRevision revision,
                                  @Nullable PropertyValue value,
                                  boolean force) throws SvnBindException {
    try {
      if (target.isFile()) {
        createClient().doSetRevisionProperty(target.getFile(), revision, property, toPropertyValue(value), force, null);
      }
      else {
        createClient().doSetRevisionProperty(target.getUrl(), revision, property, toPropertyValue(value), force, null);
      }
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @NotNull
  private static SVNProperties toSvnProperties(@NotNull PropertiesMap properties) {
    SVNProperties result = new SVNProperties();

    for (Map.Entry<String, PropertyValue> entry : properties.entrySet()) {
      result.put(entry.getKey(), toPropertyValue(entry.getValue()));
    }

    return result;
  }

  private void runGetProperty(@NotNull Target target,
                              @Nullable String property,
                              @Nullable SVNRevision revision,
                              @Nullable Depth depth,
                              @Nullable PropertyConsumer handler) throws SvnBindException {
    SVNWCClient client = createClient();

    try {
      if (target.isUrl()) {
        client.doGetProperty(target.getUrl(), property, target.getPegRevision(), revision, toDepth(depth), toHandler(handler));
      } else {
        client.doGetProperty(target.getFile(), property, target.getPegRevision(), revision, toDepth(depth), toHandler(handler), null);
      }
    } catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  private PropertyData getRevisionProperty(@NotNull Target target, @NotNull final String property, @Nullable SVNRevision revision)
    throws SVNException {
    final SVNWCClient client = createClient();
    final PropertyData[] result = new PropertyData[1];
    ISVNPropertyHandler handler = new ISVNPropertyHandler() {
      @Override
      public void handleProperty(File path, SVNPropertyData property) {
        handle(property);
      }

      @Override
      public void handleProperty(SVNURL url, SVNPropertyData property) {
        handle(property);
      }

      @Override
      public void handleProperty(long revision, SVNPropertyData property) {
        handle(property);
      }

      private void handle(@NotNull SVNPropertyData data) {
        if (property.equals(data.getName())) {
          result[0] = PropertyData.create(data);
        }
      }
    };

    if (target.isFile()) {
      client.doGetRevisionProperty(target.getFile(), null, revision, handler);
    } else {
      client.doGetRevisionProperty(target.getUrl(), null, revision, handler);
    }

    return result[0];
  }

  @Nullable
  private static ISVNPropertyHandler toHandler(@Nullable final PropertyConsumer consumer) {
    ISVNPropertyHandler result = null;

    if (consumer != null) {
      result = new ISVNPropertyHandler() {
        @Override
        public void handleProperty(File path, SVNPropertyData property) throws SVNException {
          callHandler(() -> consumer.handleProperty(path, PropertyData.create(property)));
        }

        @Override
        public void handleProperty(SVNURL url, SVNPropertyData property) throws SVNException {
          callHandler(() -> consumer.handleProperty(url, PropertyData.create(property)));
        }

        @Override
        public void handleProperty(long revision, SVNPropertyData property) throws SVNException {
          callHandler(() -> consumer.handleProperty(revision, PropertyData.create(property)));
        }

        private void callHandler(@NotNull ThrowableRunnable<SvnBindException> handler) throws SVNException {
          try {
            handler.run();
          }
          catch (SvnBindException e) {
            throw e.toSVNException();
          }
        }
      };
    }

    return result;
  }

  @Nullable
  private static SVNPropertyValue toPropertyValue(@Nullable PropertyValue value) {
    SVNPropertyValue result = null;

    if (value != null) {
      result = SVNPropertyValue.create(value.toString());
    }

    return result;
  }
}
