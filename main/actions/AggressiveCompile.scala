/* sbt -- Simple Build Tool
 * Copyright 2010 Mark Harrah
 */
package sbt

import inc._

	import java.io.File
	import compiler.{AnalyzingCompiler, CompilerArguments, JavaCompiler}
	import classpath.ClasspathUtilities
	import classfile.Analyze
	import xsbti.api.Source
	import xsbti.AnalysisCallback
	import CompileSetup._
	import CompileOrder.{JavaThenScala, Mixed, ScalaThenJava}
	import sbinary.DefaultProtocol.{ immutableMapFormat, immutableSetFormat, StringFormat }

final class CompileConfiguration(val sources: Seq[File], val classpath: Seq[File],
	val previousAnalysis: Analysis, val previousSetup: Option[CompileSetup], val currentSetup: CompileSetup, val getAnalysis: File => Option[Analysis],
	val maxErrors: Int, val compiler: AnalyzingCompiler, val javac: JavaCompiler)

class AggressiveCompile(cacheDirectory: File)
{
	def apply(compiler: AnalyzingCompiler, javac: JavaCompiler, sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String] = Nil, javacOptions: Seq[String] = Nil, analysisMap: Map[File, Analysis] = Map.empty, maxErrors: Int = 100, compileOrder: CompileOrder.Value = Mixed)(implicit log: Logger): Analysis =
	{
		val setup = new CompileSetup(outputDirectory, new CompileOptions(options, javacOptions), compiler.scalaInstance.actualVersion, compileOrder)
		compile1(sources, classpath, setup, store, analysisMap, compiler, javac, maxErrors)
	}

	def withBootclasspath(args: CompilerArguments, classpath: Seq[File]): Seq[File] =
		args.bootClasspath ++ args.finishClasspath(classpath)

	def compile1(sources: Seq[File], classpath: Seq[File], setup: CompileSetup, store: AnalysisStore, analysis: Map[File, Analysis], compiler: AnalyzingCompiler, javac: JavaCompiler, maxErrors: Int)(implicit log: Logger): Analysis =
	{
		val (previousAnalysis, previousSetup) = extract(store.get())
		val config = new CompileConfiguration(sources, classpath, previousAnalysis, previousSetup, setup, analysis.get _, maxErrors, compiler, javac)
		val (modified, result) = compile2(config)
		if(modified)
			store.set(result, setup)
		result
	}
	def compile2(config: CompileConfiguration)(implicit log: Logger, equiv: Equiv[CompileSetup]): (Boolean, Analysis) =
	{
		import config._
		import currentSetup._
		val getAPI = (f: File) => {
			val extApis = getAnalysis(f) match { case Some(a) => a.apis.external; case None => Map.empty[String, Source] }
			extApis.get _
		}
		val absClasspath = classpath.map(_.getCanonicalFile)
		val apiOption= (api: Either[Boolean, Source]) => api.right.toOption
		val cArgs = new CompilerArguments(compiler.scalaInstance, compiler.cp)
		val searchClasspath = withBootclasspath(cArgs, absClasspath)
		val entry = Locate.entry(searchClasspath)
		
		val compile0 = (include: Set[File], callback: AnalysisCallback) => {
			IO.createDirectory(outputDirectory)
			val incSrc = sources.filter(include)
			val (javaSrcs, scalaSrcs) = incSrc partition javaOnly
			println("Compiling:\n\t" + incSrc.mkString("\n\t"))
			def compileScala() =
				if(!scalaSrcs.isEmpty)
				{
					val sources = if(order == Mixed) incSrc else scalaSrcs
					val arguments = cArgs(sources, absClasspath, outputDirectory, options.options)
					compiler.compile(arguments, callback, maxErrors, log)
				}
			def compileJava() =
				if(!javaSrcs.isEmpty)
				{
					import Path._
					val loader = ClasspathUtilities.toLoader(absClasspath, compiler.scalaInstance.loader)
					def readAPI(source: File, classes: Seq[Class[_]]) { callback.api(source, ClassToAPI(classes)) }
					Analyze(outputDirectory, javaSrcs, log)(callback, loader, readAPI) {
						javac(javaSrcs, absClasspath, outputDirectory, options.javacOptions)
					}
				}
			if(order == JavaThenScala) { compileJava(); compileScala() } else { compileScala(); compileJava() }
		}
		
		val sourcesSet = sources.toSet
		val analysis = previousSetup match {
			case Some(previous) if equiv.equiv(previous, currentSetup) => previousAnalysis
			case _ => Incremental.prune(sourcesSet, previousAnalysis)
		}
		IncrementalCompile(sourcesSet, entry, compile0, analysis, getAnalysis, outputDirectory)
	}
	private def extract(previous: Option[(Analysis, CompileSetup)]): (Analysis, Option[CompileSetup]) =
		previous match
		{
			case Some((an, setup)) => (an, Some(setup))
			case None => (Analysis.Empty, None)
		}
	def javaOnly(f: File) = f.getName.endsWith(".java")

	import AnalysisFormats._
	val store = AggressiveCompile.staticCache(cacheDirectory, AnalysisStore.sync(AnalysisStore.cached(FileBasedStore(cacheDirectory))))
}
private object AggressiveCompile
{
		import collection.mutable
		import java.lang.ref.{Reference,SoftReference}
	private[this] val cache = new collection.mutable.HashMap[File, Reference[AnalysisStore]]
	private def staticCache(file: File, backing: => AnalysisStore): AnalysisStore =
		synchronized {
			cache get file flatMap { ref => Option(ref.get) } getOrElse {
				val b = backing
				cache.put(file, new SoftReference(b))
				b
			}
		}
}