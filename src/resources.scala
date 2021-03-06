package android

import java.io.File

import android.Dependencies.{AarLibrary, ApkLibrary, LibraryDependency, LibraryProject}
import com.android.builder.core.{VariantType, AaptPackageProcessBuilder, AndroidBuilder}
import com.android.builder.model.AaptOptions
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.builder.png.VectorDrawableRenderer
import com.android.ide.common.res2._
import com.android.resources.Density
import com.android.utils.ILogger
import sbt.Keys.TaskStreams
import sbt._

import collection.JavaConverters._

import language.postfixOps

import Dependencies.LibrarySeqOps

object Resources {

  def doCollectResources( bldr: AndroidBuilder
                          , minSdk: Int
                          , noTestApk: Boolean
                          , isLib: Boolean
                          , libs: Seq[LibraryDependency]
                          , layout: ProjectLayout
                          , extraAssets: Seq[File]
                          , extraRes: Seq[File]
                          , renderVectors: Boolean
                          , logger: Logger => ILogger
                          , cache: File
                          , s: TaskStreams
                          )(implicit m: BuildOutput.Converter): (File,File) = {

    val assetBin = layout.mergedAssets
    val assets = layout.assets
    val resTarget = layout.mergedRes
    val rsResources = layout.rsRes

    resTarget.mkdirs()
    assetBin.mkdirs

    val depassets = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getAssetsFolder.isDirectory => n.getAssetsFolder }
    // copy assets to single location
    depassets ++ (libs collect {
      case r if r.layout.assets.isDirectory => r.layout.assets
    }) foreach { a => IO.copyDirectory(a, assetBin, false, true) }
    extraAssets foreach { a =>
      if (a.isDirectory) IO.copyDirectory(a, assetBin, false, true)
    }

    if (assets.exists) IO.copyDirectory(assets, assetBin, false, true)
    if (noTestApk && layout.testAssets.exists)
      IO.copyDirectory(layout.testAssets, assetBin, false, true)
    // prepare resource sets for merge
    val res = extraRes ++ Seq(layout.res, rsResources) ++
      (libs map { _.layout.res } filter { _.isDirectory })

    s.log.debug("Local/library-project resources: " + res)
    // this needs to wait for other projects to at least finish their
    // apklibs tasks--handled if androidBuild() is called properly
    val depres = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getResFolder.isDirectory => n.getResFolder }
    s.log.debug("apklib/aar resources: " + depres)

    val respaths = depres ++ res.reverse ++
      (if (layout.res.isDirectory) Seq(layout.res) else Seq.empty) ++
      (if (noTestApk && layout.testRes.isDirectory)
        Seq(layout.res) else Seq.empty)
    val vectorprocessor = new VectorDrawableRenderer(
      if (renderVectors) minSdk else math.max(minSdk,21),
      layout.generatedVectors, Set(Density.MEDIUM,
        Density.HIGH,
        Density.XHIGH,
        Density.XXHIGH).asJava,
      SbtLogger(s.log))
    val sets = respaths.distinct flatMap { r =>
      val set = new ResourceSet(r.getAbsolutePath)
      set.addSource(r)

      set.setPreprocessor(vectorprocessor)
      val generated = new GeneratedResourceSet(set)
      set.setGeneratedSet(generated)

      s.log.debug("Adding resource path: " + r)
      List(generated, set)
    }

    val inputs = (respaths flatMap { r => (r ***) get }) filter (n =>
      !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    FileFunction.cached(cache / "nuke-res-if-changed", FilesInfo.lastModified) { in =>
      IO.delete(resTarget)
      in
    }(depres.toSet)
    FileFunction.cached(cache / "collect-resources")(
      FilesInfo.lastModified, FilesInfo.exists) { (inChanges,outChanges) =>
      s.log.info("Collecting resources")
      incrResourceMerge(layout, minSdk, resTarget, isLib, libs,
        cache / "collect-resources", logger(s.log), bldr, sets, vectorprocessor, inChanges, s.log)
      ((resTarget ** FileOnlyFilter).get ++ (layout.generatedVectors ** FileOnlyFilter).get).toSet
    }(inputs.toSet)

    (assetBin, resTarget)
  }
  def incrResourceMerge(layout: ProjectLayout, minSdk: Int, resTarget: File, isLib: Boolean,
                        libs: Seq[LibraryDependency], blobDir: File, logger: ILogger,
                        bldr: AndroidBuilder, resources: Seq[ResourceSet],
                        preprocessor: ResourcePreprocessor,
                        changes: ChangeReport[File],
                        slog: Logger)(implicit m: BuildOutput.Converter) {

    def merge() = fullResourceMerge(layout, minSdk, resTarget, isLib, libs, blobDir,
      logger, bldr, resources, preprocessor, slog)
    val merger = new ResourceMerger
    if (!merger.loadFromBlob(blobDir, true)) {
      slog.debug("Could not load merge blob (no full merge yet?)")
      merge()
    } else if (!merger.checkValidUpdate(resources.asJava)) {
      slog.debug("requesting full merge: !checkValidUpdate")
      merge()
    } else {

      val fileValidity = new FileValidity[ResourceSet]
      val exists = changes.added ++ changes.removed ++ changes.modified exists {
        file =>
          val status = if (changes.added contains file)
            FileStatus.NEW
          else if (changes.removed contains file)
            FileStatus.REMOVED
          else if (changes.modified contains file)
            FileStatus.CHANGED
          else
            sys.error("Unknown file status: " + file)

          merger.findDataSetContaining(file, fileValidity)
          val vstatus = fileValidity.getStatus

          if (vstatus == FileValidity.FileStatus.UNKNOWN_FILE) {
            merge()
            slog.debug("Incremental merge aborted, unknown file: " + file)
            true
          } else if (vstatus == FileValidity.FileStatus.VALID_FILE) {
            // begin workaround
            // resource merger doesn't seem to actually copy changed files over...
            // values.xml gets merged, but if files are changed...
            val targetFile = resTarget / (
              file relativeTo fileValidity.getSourceFile).get.getPath
            val copy = Seq((file, targetFile))
            status match {
              case FileStatus.NEW =>
              case FileStatus.CHANGED =>
                if (targetFile.exists) IO.copy(copy, false, true)
              case FileStatus.REMOVED => targetFile.delete()
            }
            // end workaround
            try {
              if (!fileValidity.getDataSet.updateWith(
                fileValidity.getSourceFile, file, status, logger)) {
                slog.debug("Unable to handle changed file: " + file)
                merge()
                true
              } else
                false
            } catch {
              case e: RuntimeException =>
                slog.warn("Unable to handle changed file: " + file + ": " + e)
                merge()
                true
            }
          } else
            false
      }
      if (!exists) {
        slog.info("Performing incremental resource merge")
        val writer = new MergedResourceWriter(resTarget,
          bldr.getAaptCruncher(SbtProcessOutputHandler(slog)),
          true, true, layout.publicTxt, layout.mergeBlame,
          preprocessor)
        merger.mergeData(writer, true)
        merger.writeBlobTo(blobDir, writer)
      }
    }
  }
  def fullResourceMerge(layout: ProjectLayout, minSdk: Int, resTarget: File, isLib: Boolean,
                        libs: Seq[LibraryDependency], blobDir: File, logger: ILogger,
                        bldr: AndroidBuilder, resources: Seq[ResourceSet],
                        preprocessor: ResourcePreprocessor, slog: Logger)(implicit m: BuildOutput.Converter) {

    slog.info("Performing full resource merge")
    val merger = new ResourceMerger

    resTarget.mkdirs()

    resources foreach { r =>
      r.loadFromFiles(logger)
      merger.addDataSet(r)
    }
    val writer = new MergedResourceWriter(resTarget,
      bldr.getAaptCruncher(SbtProcessOutputHandler(slog)),
      true, true, layout.publicTxt, layout.mergeBlame, preprocessor)
    merger.mergeData(writer, false)
    merger.writeBlobTo(blobDir, writer)
  }

  def aapt(bldr: AndroidBuilder, manifest: File, pkg: String,
           extraParams: Seq[String],
           libs: Seq[LibraryDependency], lib: Boolean, debug: Boolean,
           res: File, assets: File, resApk: String, gen: File, proguardTxt: String,
           logger: Logger) = synchronized {

    gen.mkdirs()
    val options = new AaptOptions {
      override def getIgnoreAssets = null
      override def getNoCompress = null
      override def getFailOnMissingConfigEntry = false
      override def getAdditionalParameters = extraParams.asJava
    }
    val genPath = gen.getAbsolutePath
    val all = collectdeps(libs)
    logger.debug("All libs: " + all)
    logger.debug("packageForR: " + pkg)
    logger.debug("proguard.txt: " + proguardTxt)
    val aaptCommand = new AaptPackageProcessBuilder(manifest, options)
    if (res.isDirectory)
      aaptCommand.setResFolder(res)
    if (assets.isDirectory)
      aaptCommand.setAssetsFolder(assets)
    aaptCommand.setLibraries(all.asJava)
    aaptCommand.setPackageForR(pkg)
    aaptCommand.setResPackageOutput(resApk)
    aaptCommand.setSourceOutputDir(if (resApk == null) genPath else null)
    aaptCommand.setSymbolOutputDir(if (resApk == null) genPath else null)
    aaptCommand.setProguardOutput(proguardTxt)
    aaptCommand.setType(if (lib) VariantType.LIBRARY else VariantType.DEFAULT)
    aaptCommand.setDebuggable(debug)
    bldr.processResources(aaptCommand, true, SbtProcessOutputHandler(logger))
  }

  def collectdeps(libs: Seq[AndroidLibrary]): Seq[AndroidLibrary] = {
    libs
      .map(_.getDependencies.asScala)
      .flatMap(collectdeps)
      .++(libs)
      .distinctLibs
  }
}
