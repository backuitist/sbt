/* sbt -- Simple Build Tool
 * Copyright 2008, 2010  Mark Harrah
 */
package sbt

import java.io.File
import xsbti.{AppConfiguration, AppProvider, ScalaProvider}
import inc.Analysis

/** Represents the minimal information necessary to construct a Project.
*
* `projectDirectory` is the base directory for the project (not the root project directory)
* `builderPath` is the base directory for the project (not the root project directory)
* `dependencies` are the Projects that this Project depends on.
* `parent` is the parent Project, or None if this is the root project.
* `buildScalaVersion` contains the explicitly requested Scala version to use  for building (as when using `+` or `++`) or None if the normal version should be used.
*/
final case class ProjectInfo(name: Option[String], projectDirectory: File, builderDir: File, dependencies: Iterable[ProjectDependency], parent: Option[Project])(
	val configuration: AppConfiguration, val analysis: Analysis, val compileInputs: Compile.Inputs, val construct: File => Project, external: ExternalProjects)
{
	def app = configuration.provider
	/** The version of Scala running sbt.*/
	def definitionScalaVersion = app.scalaProvider.version
	/** The launcher instance that booted sbt.*/
	def launcher = app.scalaProvider.launcher
	def globalLock = launcher.globalLock
	/* cannot be referenced in a Project's constructor */
	lazy val externals = external.value
}
final class ExternalProjects(externalMap: => Map[File, Project])
{
	lazy val value = externalMap
}

object ProjectInfo
{
	def externals(externalMap: => Map[File, Project]) = new ExternalProjects(externalMap)
	val MetadataDirectoryName = "project"
}