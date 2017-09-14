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
package com.intellij.openapi.vcs.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryAdapter
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndex
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusListener
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.ex.LineStatusTracker
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.*
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.QueueProcessorRemovePartner
import com.intellij.util.containers.HashMap
import org.jetbrains.annotations.CalledInAwt
import org.jetbrains.annotations.NonNls
import java.nio.charset.Charset

class LineStatusTrackerManager(
  private val project: Project,
  private val statusProvider: VcsBaseContentProvider,
  private val application: Application,
  @Suppress("UNUSED_PARAMETER") makeSureIndexIsInitializedFirst: DirectoryIndex
) : ProjectComponent, LineStatusTrackerManagerI {

  private val LOCK = Any()
  private val disposable: Disposable = Disposer.newDisposable()
  private var isDisposed = false

  private val trackers = HashMap<Document, TrackerData>()

  private val queue: QueueProcessorRemovePartner<Document, BaseRevisionLoader> = QueueProcessorRemovePartner(project)
  private var ourLoadCounter: Long = 0

  companion object {
    private val LOG = Logger.getInstance("#com.intellij.openapi.vcs.impl.LineStatusTrackerManager")

    @JvmStatic
    fun getInstance(project: Project): LineStatusTrackerManagerI {
      return project.getComponent(LineStatusTrackerManagerI::class.java)
    }
  }

  override fun initComponent() {
    StartupManager.getInstance(project).registerPreStartupActivity {
      if (isDisposed) return@registerPreStartupActivity

      val busConnection = project.messageBus.connect(disposable)
      busConnection.subscribe(LineStatusTrackerSettingListener.TOPIC, MyLineStatusTrackerSettingListener())

      val fsManager = FileStatusManager.getInstance(project)
      fsManager.addFileStatusListener(MyFileStatusListener(), disposable)

      val editorFactory = EditorFactory.getInstance()
      editorFactory.addEditorFactoryListener(MyEditorFactoryListener(), disposable)

      val virtualFileManager = VirtualFileManager.getInstance()
      virtualFileManager.addVirtualFileListener(MyVirtualFileListener(), disposable)
    }
  }

  override fun disposeComponent() {
    isDisposed = true
    Disposer.dispose(disposable)

    synchronized(LOCK) {
      for (data in trackers.values) {
        data.tracker.release()
      }

      trackers.clear()
      queue.clear()
    }
  }

  @NonNls
  override fun getComponentName(): String {
    return "LineStatusTrackerManager"
  }

  override fun getLineStatusTracker(document: Document): LineStatusTracker<*>? {
    synchronized(LOCK) {
      if (isDisposed) return null
      return trackers[document]?.tracker
    }
  }

  private fun isTrackedEditor(editor: Editor): Boolean {
    return editor.project == null || editor.project === project
  }

  private fun getAllTrackedEditors(document: Document): List<Editor> {
    return EditorFactory.getInstance().getEditors(document, project).asList()
  }

  private fun getAllTrackedEditors(): List<Editor> {
    return EditorFactory.getInstance().allEditors.filter { isTrackedEditor(it) }
  }

  @CalledInAwt
  private fun onEditorCreated(editor: Editor) {
    if (isTrackedEditor(editor)) {
      installTracker(editor.document)
    }
  }

  @CalledInAwt
  private fun onEditorRemoved(editor: Editor) {
    if (isTrackedEditor(editor)) {
      val editors = getAllTrackedEditors(editor.document)
      if (editors.isEmpty() || editors.size == 1 && editor === editors[0]) {
        doReleaseTracker(editor.document)
      }
    }
  }

  @CalledInAwt
  private fun onEverythingChanged() {
    synchronized(LOCK) {
      if (isDisposed) return
      log("onEverythingChanged", null)

      val trackers = trackers.values.map { it.tracker }
      for (tracker in trackers) {
        resetTracker(tracker.document, tracker.virtualFile, tracker)
      }

      for (editor in getAllTrackedEditors()) {
        installTracker(editor.document)
      }
    }
  }

  @CalledInAwt
  private fun onFileChanged(virtualFile: VirtualFile) {
    val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile) ?: return

    synchronized(LOCK) {
      if (isDisposed) return
      log("onFileChanged", virtualFile)
      resetTracker(document, virtualFile, getLineStatusTracker(document))
    }
  }

  private fun installTracker(document: Document) {
    val file = FileDocumentManager.getInstance().getFile(document)
    log("installTracker", file)
    if (shouldBeInstalled(file)) {
      doInstallTracker(file!!, document)
    }
  }

  private fun resetTracker(document: Document, virtualFile: VirtualFile, tracker: LineStatusTracker<*>?) {
    val isOpened = !getAllTrackedEditors(document).isEmpty()
    val shouldBeInstalled = isOpened && shouldBeInstalled(virtualFile)

    log("resetTracker: shouldBeInstalled - " + shouldBeInstalled + ", tracker - " + if (tracker == null) "null" else "found", virtualFile)

    if (tracker != null && shouldBeInstalled) {
      doRefreshTracker(tracker)
    }
    else if (tracker != null) {
      doReleaseTracker(document)
    }
    else if (shouldBeInstalled) {
      doInstallTracker(virtualFile, document)
    }
  }

  private fun shouldBeInstalled(virtualFile: VirtualFile?): Boolean {
    if (isDisposed) return false

    if (virtualFile == null || virtualFile is LightVirtualFile) return false

    val statusManager = FileStatusManager.getInstance(project) ?: return false

    if (!statusProvider.isSupported(virtualFile)) {
      log("shouldBeInstalled failed: file not supported", virtualFile)
      return false
    }

    val status = statusManager.getStatus(virtualFile)
    if (status === FileStatus.NOT_CHANGED || status === FileStatus.ADDED || status === FileStatus.UNKNOWN || status === FileStatus.IGNORED) {
      log("shouldBeInstalled skipped: status=" + status, virtualFile)
      return false
    }
    return true
  }

  private fun doRefreshTracker(tracker: LineStatusTracker<*>) {
    synchronized(LOCK) {
      if (isDisposed) return

      log("trackerRefreshed", tracker.virtualFile)
      startAlarm(tracker.document, tracker.virtualFile)
    }
  }

  private fun doReleaseTracker(document: Document) {
    synchronized(LOCK) {
      if (isDisposed) return

      queue.remove(document)
      val data = trackers.remove(document)
      if (data != null) {
        log("trackerReleased", data.tracker.virtualFile)
        data.tracker.release()
      }
    }
  }

  private fun doInstallTracker(virtualFile: VirtualFile, document: Document) {
    synchronized(LOCK) {
      if (isDisposed) return

      if (trackers.containsKey(document)) return
      assert(!queue.containsKey(document))

      log("trackerInstalled", virtualFile)
      val tracker = LineStatusTracker.createOn(virtualFile, document, project, getTrackerMode())
      trackers.put(document, TrackerData(tracker))

      startAlarm(document, virtualFile)
    }
  }

  private fun getTrackerMode(): LineStatusTracker.Mode {
    val vcsApplicationSettings = VcsApplicationSettings.getInstance()
    if (!vcsApplicationSettings.SHOW_LST_GUTTER_MARKERS) return LineStatusTracker.Mode.SILENT
    return if (vcsApplicationSettings.SHOW_WHITESPACES_IN_LST) LineStatusTracker.Mode.SMART else LineStatusTracker.Mode.DEFAULT
  }

  private fun startAlarm(document: Document, virtualFile: VirtualFile) {
    synchronized(LOCK) {
      queue.add(document, BaseRevisionLoader(document, virtualFile))
    }
  }

  private inner class BaseRevisionLoader(private val document: Document,
                                         private val virtualFile: VirtualFile) : Runnable {

    override fun run() {
      val result = try {
        loadBaseRevision()
      }
      catch (e: Exception) {
        LOG.error(e)
        RefreshResult.Error
      }

      handleNewBaseRevision(result)
    }

    private fun loadBaseRevision(): RefreshResult {
      if (isDisposed) return RefreshResult.Canceled

      if (!virtualFile.isValid) {
        log("BaseRevisionLoader failed: virtual file not valid", virtualFile)
        return RefreshResult.Error
      }

      val baseContent = statusProvider.getBaseRevision(virtualFile)
      if (baseContent == null) {
        log("BaseRevisionLoader failed: null returned for base revision", virtualFile)
        return RefreshResult.Error
      }

      // loads are sequential (in single threaded QueueProcessor);
      // so myLoadCounter can't take less value for greater base revision -> the only thing we want from it
      val newContentInfo = ContentInfo(baseContent.revisionNumber, virtualFile.charset, ourLoadCounter)
      ourLoadCounter++

      synchronized(LOCK) {
        val data = trackers[document]
        if (data == null) {
          log("BaseRevisionLoader canceled: tracker already released", virtualFile)
          return RefreshResult.Canceled
        }
        if (!shouldBeUpdated(data.contentInfo, newContentInfo)) {
          log("BaseRevisionLoader canceled: no need to update", virtualFile)
          return RefreshResult.Canceled
        }
      }

      val lastUpToDateContent = baseContent.loadContent()
      if (lastUpToDateContent == null) {
        log("BaseRevisionLoader failed: can't load up-to-date content", virtualFile)
        return RefreshResult.Error
      }

      val converted = StringUtil.convertLineSeparators(lastUpToDateContent)
      return RefreshResult.Success(converted, newContentInfo)
    }

    private fun handleNewBaseRevision(result: RefreshResult) {
      when (result) {
        is RefreshResult.Canceled -> {
        }
        is RefreshResult.Error -> {
          synchronized(LOCK) {
            doReleaseTracker(document)
          }
        }
        is RefreshResult.Success -> {
          application.invokeLater(Runnable {
            synchronized(LOCK) {
              val data = trackers[document]
              if (data == null) {
                log("BaseRevisionLoader initializing: tracker already released", virtualFile)
                return@Runnable
              }
              if (!shouldBeUpdated(data.contentInfo, result.info)) {
                log("BaseRevisionLoader initializing: no need to update", virtualFile)
                return@Runnable
              }

              log("BaseRevisionLoader initializing: success", virtualFile)
              trackers.put(document, TrackerData(data.tracker, result.info))
              data.tracker.setBaseRevision(result.text)
            }
          }, ModalityState.NON_MODAL, Condition<Any> { isDisposed })
        }
      }
    }
  }

  private inner class MyFileStatusListener : FileStatusListener {
    override fun fileStatusesChanged() {
      onEverythingChanged()
    }

    override fun fileStatusChanged(virtualFile: VirtualFile) {
      onFileChanged(virtualFile)
    }
  }

  private inner class MyEditorFactoryListener : EditorFactoryAdapter() {
    override fun editorCreated(event: EditorFactoryEvent) {
      onEditorCreated(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
      onEditorRemoved(event.editor)
    }
  }

  private inner class MyVirtualFileListener : VirtualFileListener {
    override fun beforeContentsChange(event: VirtualFileEvent) {
      if (event.isFromRefresh) {
        onFileChanged(event.file)
      }
    }

    override fun propertyChanged(event: VirtualFilePropertyEvent) {
      if (VirtualFile.PROP_ENCODING == event.propertyName) {
        onFileChanged(event.file)
      }
    }
  }

  private inner class MyLineStatusTrackerSettingListener : LineStatusTrackerSettingListener {
    override fun settingsUpdated() {
      synchronized(LOCK) {
        val mode = getTrackerMode()
        for (data in trackers.values) {
          data.tracker.mode = mode
        }
      }
    }
  }


  private fun shouldBeUpdated(oldInfo: ContentInfo?, newInfo: ContentInfo): Boolean {
    if (oldInfo == null) return true
    if (oldInfo.revision == newInfo.revision && oldInfo.revision != VcsRevisionNumber.NULL) {
      return oldInfo.charset != newInfo.charset
    }
    return oldInfo.loadCounter < newInfo.loadCounter
  }

  private class TrackerData(val tracker: LineStatusTracker<*>,
                            var contentInfo: ContentInfo? = null)

  private class ContentInfo(val revision: VcsRevisionNumber, val charset: Charset, val loadCounter: Long)


  private sealed class RefreshResult {
    class Success(val text: String, val info: ContentInfo) : RefreshResult()
    object Canceled : RefreshResult()
    object Error : RefreshResult()
  }


  private fun log(message: String, file: VirtualFile?) {
    if (LOG.isDebugEnabled) {
      if (file != null) {
        LOG.debug(message + "; file: " + file.path)
      }
      else {
        LOG.debug(message)
      }
    }
  }
}
