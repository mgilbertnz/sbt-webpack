
sbtPlugin := true

organization := "com.github.mgilbertnz.sbt"

name := "sbt-webpack"

scalaVersion := "2.12.4"

resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.jcenterRepo
)

libraryDependencies += "org.webjars" % "npm" % "4.0.2"
libraryDependencies += "org.webjars.npm" % "lodash" % "4.17.2"

addSbtPlugin("com.typesafe.sbt" %% "sbt-js-engine" % "1.2.2")

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

publishTo := {
  val nexus = "http://nexus.financialplatforms.co.nz:8081/nexus/content/repositories/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "snapshots")
  else
    Some("releases" at nexus + "releases")
}
credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
publishMavenStyle := true