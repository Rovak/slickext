name := "slickext"

version := "0.0.4"

scalaVersion := "2.11.6"


libraryDependencies ++= Seq(
  "org.scala-lang"     % "scala-compiler" % scalaVersion.value,
  "com.typesafe.slick" %% "slick"         % "3.0.0",
  "org.scalatest"      %% "scalatest"     % "2.2.4"              % "test",
  "com.h2database"     %   "h2"           % "1.4.187"            % "test"
)

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full)

scalacOptions ++= Seq("-deprecation")

seq(bintraySettings:_*)

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
