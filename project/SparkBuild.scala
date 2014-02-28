/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._
import sbt.Classpaths.publishTask
import Keys._
import scala.util.Properties
// For Sonatype publishing
//import com.jsuereth.pgp.sbtplugin.PgpKeys._

object SparkBuild extends Build {
  // Hadoop version to build against. For example, "1.0.4" for Apache releases, or
  // "2.0.0-mr1-cdh4.2.0" for Cloudera Hadoop. Note that these variables can be set
  // through the environment variables SPARK_HADOOP_VERSION and SPARK_YARN.
  val DEFAULT_HADOOP_VERSION = "1.0.4"

  // Whether the Hadoop version to build against is 2.2.x, or a variant of it. This can be set
  // through the SPARK_IS_NEW_HADOOP environment variable.
  val DEFAULT_IS_NEW_HADOOP = false

  val DEFAULT_YARN = false

  // HBase version; set as appropriate.
  val HBASE_VERSION = "0.94.6"

  // Target JVM version
  val SCALAC_JVM_VERSION = "jvm-1.6"
  val JAVAC_JVM_VERSION = "1.6"

  lazy val root = Project("root", file("."), settings = rootSettings) aggregate(allProjects: _*)

  lazy val core = Project("core", file("core"), settings = coreSettings)

  lazy val repl = Project("repl", file("repl"), settings = replSettings)
    .dependsOn(core, graphx, bagel, mllib)

  lazy val tools = Project("tools", file("tools"), settings = toolsSettings) dependsOn(core) dependsOn(streaming)

  lazy val bagel = Project("bagel", file("bagel"), settings = bagelSettings) dependsOn(core)

  lazy val graphx = Project("graphx", file("graphx"), settings = graphxSettings) dependsOn(core)

  lazy val streaming = Project("streaming", file("streaming"), settings = streamingSettings) dependsOn(core)

  lazy val mllib = Project("mllib", file("mllib"), settings = mllibSettings) dependsOn(core)

  // A configuration to set an alternative publishLocalConfiguration
  lazy val MavenCompile = config("m2r") extend(Compile)
  lazy val publishLocalBoth = TaskKey[Unit]("publish-local", "publish local for m2 and ivy")
  val sparkHome = System.getProperty("user.dir")

  // Allows build configuration to be set through environment variables
  lazy val hadoopVersion = Properties.envOrElse("SPARK_HADOOP_VERSION", DEFAULT_HADOOP_VERSION)
  lazy val isNewHadoop = Properties.envOrNone("SPARK_IS_NEW_HADOOP") match {
    case None => {
      val isNewHadoopVersion = "2.[2-9]+".r.findFirstIn(hadoopVersion).isDefined
      (isNewHadoopVersion|| DEFAULT_IS_NEW_HADOOP)
    }
    case Some(v) => v.toBoolean
  }

  lazy val isYarnEnabled = Properties.envOrNone("SPARK_YARN") match {
    case None => DEFAULT_YARN
    case Some(v) => v.toBoolean
  }
  lazy val hadoopClient = if (hadoopVersion.startsWith("0.20.") || hadoopVersion == "1.0.0") "hadoop-core" else "hadoop-client"
  
  // Include Ganglia integration if the user has enabled Ganglia
  // This is isolated from the normal build due to LGPL-licensed code in the library
  lazy val isGangliaEnabled = Properties.envOrNone("SPARK_GANGLIA_LGPL").isDefined
  lazy val gangliaProj = Project("spark-ganglia-lgpl", file("extras/spark-ganglia-lgpl"), settings = gangliaSettings).dependsOn(core)
  val maybeGanglia: Seq[ClasspathDependency] = if (isGangliaEnabled) Seq(gangliaProj) else Seq()
  val maybeGangliaRef: Seq[ProjectReference] = if (isGangliaEnabled) Seq(gangliaProj) else Seq()

  // Include the YARN project if the user has enabled YARN
  lazy val yarnAlpha = Project("yarn-alpha", file("yarn/alpha"), settings = yarnAlphaSettings) dependsOn(core)
  lazy val yarn = Project("yarn", file("yarn/stable"), settings = yarnSettings) dependsOn(core)

  lazy val maybeYarn: Seq[ClasspathDependency] = if (isYarnEnabled) Seq(if (isNewHadoop) yarn else yarnAlpha) else Seq()
  lazy val maybeYarnRef: Seq[ProjectReference] = if (isYarnEnabled) Seq(if (isNewHadoop) yarn else yarnAlpha) else Seq()

  lazy val allExternal = Seq[ClasspathDependency]()
  lazy val allExternalRefs = Seq[ProjectReference]()

  // Everything except assembly, tools and examples belong to packageProjects
  lazy val packageProjects = Seq[ProjectReference](core, repl, bagel, streaming, mllib, graphx) ++ maybeYarnRef ++ maybeGangliaRef

  lazy val allProjects = packageProjects ++ allExternalRefs ++ Seq[ProjectReference](tools)

  val ivyLocal = Resolver.file("local", file("IVY_LOCAL"))(Resolver.ivyStylePatterns)

  def sharedSettings = Defaults.defaultSettings ++ Seq(
    externalResolvers := Seq(new sbt.RawRepository(new org.fedoraproject.maven.connector.ivy.IvyResolver), ivyLocal),
    
    organization       := "org.apache.spark",
    version            := "0.9.1",
    scalaVersion       := "2.10.3",
    scalacOptions := Seq("-Xmax-classfile-name", "120", "-unchecked", "-deprecation",
      "-target:" + SCALAC_JVM_VERSION),
    javacOptions := Seq("-target", JAVAC_JVM_VERSION, "-source", JAVAC_JVM_VERSION),
    unmanagedJars in Compile <<= baseDirectory map { base => (base / "lib" ** "*.jar").classpath },
    retrieveManaged := true,
    retrievePattern := "[type]s/[artifact](-[revision])(-[classifier]).[ext]",
    transitiveClassifiers in Scope.GlobalScope := Seq("sources"),

    // Fork new JVMs for tests and set Java options for those
    fork := true,
    javaOptions in Test += "-Dspark.home=" + sparkHome,
    javaOptions in Test += "-Dspark.testing=1",
    javaOptions += "-Xmx3g",
    // Show full stack trace and duration in test cases.
    testOptions in Test += Tests.Argument("-oDF"),
    // Remove certain packages from Scaladoc
    scalacOptions in (Compile,doc) := Seq("-skip-packages", Seq(
      "akka",
      "org.apache.spark.network",
      "org.apache.spark.deploy",
      "org.apache.spark.util.collection"
      ).mkString(":")),

    // Only allow one test at a time, even across projects, since they run in the same JVM
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),

    publishMavenStyle := true,

    //useGpg in Global := true,

    pomExtra := (
      <parent>
        <groupId>org.apache</groupId>
        <artifactId>apache</artifactId>
        <version>13</version>
      </parent>
      <url>http://spark.apache.org/</url>
      <licenses>
        <license>
          <name>Apache 2.0 License</name>
          <url>http://www.apache.org/licenses/LICENSE-2.0.html</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <connection>scm:git:git@github.com:apache/spark.git</connection>
        <url>scm:git:git@github.com:apache/spark.git</url>
      </scm>
      <developers>
        <developer>
          <id>matei</id>
          <name>Matei Zaharia</name>
          <email>matei.zaharia@gmail.com</email>
          <url>http://www.cs.berkeley.edu/~matei</url>
          <organization>Apache Software Foundation</organization>
          <organizationUrl>http://spark.apache.org</organizationUrl>
        </developer>
      </developers>
      <issueManagement>
        <system>JIRA</system>
        <url>https://spark-project.atlassian.net/browse/SPARK</url>
      </issueManagement>
    ),

    /*
    publishTo <<= version { (v: String) =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("sonatype-snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("sonatype-staging"  at nexus + "service/local/staging/deploy/maven2")
    },

    */

    libraryDependencies ++= Seq(
        "io.netty"          % "netty-all"       % "4.0.13.Final",
        "org.eclipse.jetty" % "jetty-server"    % "8.1.14.v20131031"
    ),

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    parallelExecution := true,
    /* Workaround for issue #206 (fixed after SBT 0.11.0) */
    watchTransitiveSources <<= Defaults.inDependencies[Task[Seq[File]]](watchSources.task,
      const(std.TaskExtra.constant(Nil)), aggregate = true, includeRoot = true) apply { _.join.map(_.flatten) },

    otherResolvers := Seq(Resolver.file("dotM2", file(Path.userHome + "/.m2/repository"))),
    publishLocalConfiguration in MavenCompile <<= (packagedArtifacts, deliverLocal, ivyLoggingLevel) map {
      (arts, _, level) => new PublishConfiguration(None, "dotM2", arts, Seq(), level)
    },
    publishMavenStyle in MavenCompile := true,
    publishLocal in MavenCompile <<= publishTask(publishLocalConfiguration in MavenCompile, deliverLocal),
    publishLocalBoth <<= Seq(publishLocal in MavenCompile, publishLocal).dependOn
  )
  
  val slf4jVersion = "1.7.2"

  val excludeCglib = ExclusionRule(organization = "org.sonatype.sisu.inject")
  val excludeJackson = ExclusionRule(organization = "org.codehaus.jackson")
  val excludeNetty = ExclusionRule(organization = "org.jboss.netty")
  val excludeAsm = ExclusionRule(organization = "asm")
  val excludeSnappy = ExclusionRule(organization = "org.xerial.snappy")

  def coreSettings = sharedSettings ++ Seq(
    name := "spark-core",

    libraryDependencies ++= Seq(
        "com.google.guava"         % "guava"            % "14.0.1",
        "com.google.code.findbugs" % "jsr305"           % "1.3.9",
        "log4j"                    % "log4j"            % "1.2.17",
        "org.slf4j"                % "slf4j-api"        % slf4jVersion,
        "org.slf4j"                % "slf4j-log4j12"    % slf4jVersion,
        "commons-daemon"           % "commons-daemon"   % "1.0.10", // workaround for bug HADOOP-9407
        "com.ning"                 % "compress-lzf"     % "1.0.0",
        "org.xerial.snappy"        % "snappy-java"      % "1.0.5",
        "org.ow2.asm"              % "asm"              % "4.0",
        "com.typesafe.akka"  %% "akka-remote"      % "2.3.0-RC2"  excludeAll(excludeNetty),
        "com.typesafe.akka"  %% "akka-slf4j"       % "2.3.0-RC2"  excludeAll(excludeNetty),
        "org.json4s"              %% "json4s-jackson"   % "3.2.6",
        "it.unimi.dsi"             % "fastutil"         % "6.4.4",
        "colt"                     % "colt"             % "1.2.0",
        "org.apache.mesos"         % "mesos"            % "0.13.0",
        "net.java.dev.jets3t"      % "jets3t"           % "0.7.1",
        "org.apache.hadoop"        % "hadoop-client"    % hadoopVersion excludeAll(excludeJackson, excludeNetty, excludeAsm, excludeCglib),
        "org.apache.avro"          % "avro"             % "1.7.4",
        "org.apache.avro"          % "avro-ipc"         % "1.7.4" excludeAll(excludeNetty),
        "org.apache.zookeeper"     % "zookeeper"        % "3.4.5" excludeAll(excludeNetty),
        "com.codahale.metrics"     % "metrics-core"     % "3.0.0",
        "com.codahale.metrics"     % "metrics-jvm"      % "3.0.0",
        "com.codahale.metrics"     % "metrics-json"     % "3.0.0",
        "com.codahale.metrics"     % "metrics-graphite" % "3.0.0",
        "com.clearspring.analytics" % "stream"          % "2.5.1"
      )
  )

  def rootSettings = sharedSettings ++ Seq(
    publish := {}
  )

 def replSettings = sharedSettings ++ Seq(
    name := "spark-repl",
   libraryDependencies <+= scalaVersion(v => "org.scala-lang"  % "scala-compiler" % v ),
   libraryDependencies <+= scalaVersion(v => "org.scala-lang"  % "jline"          % v ),
   libraryDependencies <+= scalaVersion(v => "org.scala-lang"  % "scala-reflect"  % v )
  )

  def toolsSettings = sharedSettings ++ Seq(
    name := "spark-tools"
  )

  def graphxSettings = sharedSettings ++ Seq(
    name := "spark-graphx",
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-math3" % "3.2"
    )
  )

  def bagelSettings = sharedSettings ++ Seq(
    name := "spark-bagel"
  )

  def mllibSettings = sharedSettings ++ Seq(
    name := "spark-mllib",
    libraryDependencies ++= Seq(
      "org.jblas" % "jblas" % "1.2.3"
    )
  )

  def streamingSettings = sharedSettings ++ Seq(
    name := "spark-streaming",
    libraryDependencies ++= Seq(
      "commons-io" % "commons-io" % "2.4"
    )
  )

  def yarnCommonSettings = sharedSettings ++ Seq(
    unmanagedSourceDirectories in Compile <++= baseDirectory { base =>
      Seq(
         base / "../common/src/main/scala"
      )
    },

    unmanagedSourceDirectories in Test <++= baseDirectory { base =>
      Seq(
         base / "../common/src/test/scala"
      )
    }

  ) ++ extraYarnSettings

  def yarnAlphaSettings = yarnCommonSettings ++ Seq(
    name := "spark-yarn-alpha"
  )

  def yarnSettings = yarnCommonSettings ++ Seq(
    name := "spark-yarn"
  )

  def gangliaSettings = sharedSettings ++ Seq(
    name := "spark-ganglia-lgpl",
    libraryDependencies += "com.codahale.metrics" % "metrics-ganglia" % "3.0.0"
  )

  // Conditionally include the YARN dependencies because some tools look at all sub-projects and will complain
  // if we refer to nonexistent dependencies (e.g. hadoop-yarn-api from a Hadoop version without YARN).
  def extraYarnSettings = if(isYarnEnabled) yarnEnabledSettings else Seq()

  def yarnEnabledSettings = Seq(
    libraryDependencies ++= Seq(
      // Exclude rule required for all ?
      "org.apache.hadoop" % hadoopClient         % hadoopVersion excludeAll(excludeJackson, excludeNetty, excludeAsm, excludeCglib),
      "org.apache.hadoop" % "hadoop-yarn-api"    % hadoopVersion excludeAll(excludeJackson, excludeNetty, excludeAsm, excludeCglib),
      "org.apache.hadoop" % "hadoop-yarn-common" % hadoopVersion excludeAll(excludeJackson, excludeNetty, excludeAsm, excludeCglib),
      "org.apache.hadoop" % "hadoop-yarn-client" % hadoopVersion excludeAll(excludeJackson, excludeNetty, excludeAsm, excludeCglib)
    )
  )
}
