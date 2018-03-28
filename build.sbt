name := baseDirectory.value.getName

organization := "hobby.wei.c"

version := "0.0.1-SNAPSHOT"

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")

lazy val scalaSettings = Seq(
  scalaVersion := "2.11.7"
)

lazy val root = Project(id = "reflow", base = file("."))
  .dependsOn(/*lang*/)
  .settings(scalaSettings,
    aggregate in update := false
  )

offline := true

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "com.github.dedge-space" % "Annoguard" % "1.0.3-beta",
  "com.github.dedge-space" % "scala-lang" % "eab7368bea",

  // ScalaTest 的标准引用。
  "junit" % "junit" % "[4.12,)" % "test",
  "org.scalatest" %% "scalatest" % "[2.11,)" % "test"
)
